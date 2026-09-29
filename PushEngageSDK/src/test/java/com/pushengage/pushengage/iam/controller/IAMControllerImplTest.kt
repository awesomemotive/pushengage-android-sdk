package com.pushengage.pushengage.iam.controller

import android.app.Activity
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pushengage.pushengage.Database.PERoomDatabase
import com.pushengage.pushengage.helper.PEConstants
import com.pushengage.pushengage.helper.PEPrefs
import com.pushengage.pushengage.iam.IAMTestDb
import com.pushengage.pushengage.iam.display.IAMDisplayManagerImpl
import com.pushengage.pushengage.iam.model.IAMMessageResponse
import com.pushengage.pushengage.iam.model.IAMPosition
import com.pushengage.pushengage.iam.model.IAMTriggerCondition
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.iam.rules.IAMDevicePropertiesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Crash-safety and end-to-end behavior of the IAM orchestrator: every public
 * entry point must be safe before initialization, malformed stored data must
 * never crash trigger processing, and the trigger → queue → display → dismiss
 * → advance loop must work against the real repository, queue, and rules
 * engine (only the network is absent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMControllerImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var prefs: PEPrefs
    private lateinit var controller: IAMControllerImpl
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        // IAMDevicePropertiesManager is a process-wide singleton and caches its
        // SharedPreferences. Without this reset it stays bound to whichever test class
        // created it first, so attribute reads/writes here would silently target a
        // different store — which made the regression tests below pass vacuously.
        val instanceField = com.pushengage.pushengage.iam.rules.IAMDevicePropertiesManager::class.java
            .getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
        context.getSharedPreferences("pe_iam_user_attributes", 0).edit().clear().commit()

        IAMTestDb.clearIamTables(context)
        prefs = PEPrefs(context)
        prefs.siteKey = "" // no App ID → controller skips network sync/WorkManager
        // IAM switched on for the site, which a successful metadata sync records.
        // The display path gates on this, so without it nothing would enqueue.
        prefs.iamStatus = PEConstants.ACTIVE
        controller = IAMControllerImpl()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        controller.shutdown()
        com.pushengage.pushengage.helper.PELogger.enableLogging(false)
        // Restore the database singleton a query-recorder test replaced. Other
        // test classes bind the static IAMRepository.getInstance singleton to
        // the CURRENT database instance — leaving a different instance (or
        // null) behind desynchronizes the two singletons for every class that
        // runs after this one.
        if (databaseWasReplaced) {
            replaceDatabaseSingleton(previousDatabase)
            databaseWasReplaced = false
            previousDatabase = null
        }
        IAMTestDb.clearIamTables(context)
    }

    // -------------------------------------------------------------- utilities

    private fun initialize() {
        controller.initialize(context, prefs)
        controller.resume()
        controller.updateCurrentActivity(activity)
    }

    private fun campaign(
        id: String,
        trigger: IAMTriggerCondition,
        priority: Int = 1
    ) = IAMMessageResponse(
        id, IAMPosition.CENTER, "<html><body>$id</body></html>", 0L, false,
        emptyMap(), null, null, priority, null, null, trigger
    )

    private fun seed(vararg campaigns: IAMMessageResponse) {
        val latch = CountDownLatch(1)
        controller.repository.replaceAllMessages(campaigns.toList()) { latch.countDown() }
        assertTrue("seed write must commit", latch.await(5, TimeUnit.SECONDS))
    }

    private fun displayingId(): String? =
        (controller.displayManager as IAMDisplayManagerImpl).displayingMessageId

    /**
     * Seeds a campaign row before the controller is initialized. Same repository
     * instance the controller will use — there is only one per process.
     */
    private fun seedCampaignRow(repository: IAMRepository, id: String) {
        val latch = CountDownLatch(1)
        repository.replaceAllMessages(
            listOf(campaign(id, IAMTriggerCondition("custom", "sale", null)))
        ) { latch.countDown() }
        assertTrue("seed write must commit", latch.await(5, TimeUnit.SECONDS))
    }

    /** Polls until [condition] holds — repository writes land on its own executor. */
    private fun awaitRepositoryCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(message, condition())
    }

    /**
     * Waits for the async trigger → queue → main-thread display chain by
     * idling the main looper while polling.
     */
    private fun awaitDisplayed(expectedId: String?) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            looper.idle()
            if (displayingId() == expectedId) return
            Thread.sleep(20)
        }
        assertEquals("timed out waiting for display state", expectedId, displayingId())
    }

    /** Idles long enough for any async display work to have happened. */
    private fun settleAndAssertNothingDisplayed() {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(20) {
            looper.idle()
            Thread.sleep(10)
        }
        assertNull("no message should be displaying", displayingId())
    }

    // ------------------------------------------------ main-thread DB discipline

    /**
     * Replaces the database singleton with an in-memory instance whose query
     * callback runs on the querying thread (direct executor), recording every
     * data statement against an IAM table. With [mainThreadOnly] only
     * statements issued from the main thread are recorded — the production DB
     * allows main-thread queries, so jank/ANR-grade I/O is otherwise invisible.
     */
    private var previousDatabase: PERoomDatabase? = null
    private var databaseWasReplaced = false

    private fun installIamQueryRecorder(mainThreadOnly: Boolean): List<String> {
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, PERoomDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                { sql, _ ->
                    val statement = sql.trimStart().uppercase()
                    val isDataStatement = statement.startsWith("SELECT") ||
                        statement.startsWith("INSERT") ||
                        statement.startsWith("UPDATE") ||
                        statement.startsWith("DELETE")
                    val onMainThread = Looper.myLooper() == Looper.getMainLooper()
                    if (isDataStatement && sql.contains("iam_") && (onMainThread || !mainThreadOnly)) {
                        recorded.add(sql)
                    }
                },
                { it.run() }
            )
            .build()
        replaceDatabaseSingleton(db)
        return recorded
    }

    private fun replaceDatabaseSingleton(db: PERoomDatabase?) {
        val field = PERoomDatabase::class.java.getDeclaredField("peRoomDatabaseInstance")
        field.isAccessible = true
        if (!databaseWasReplaced) {
            previousDatabase = field.get(null) as PERoomDatabase?
            databaseWasReplaced = true
        }
        field.set(null, db)
    }

    /** Gives the controller's single-thread background executor time to drain. */
    private fun drainBackgroundWork() {
        repeat(15) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `initialize performs no IAM database work on the main thread even with logging enabled`() {
        val mainThreadQueries = installIamQueryRecorder(mainThreadOnly = true)
        com.pushengage.pushengage.helper.PELogger.enableLogging(true)

        controller.initialize(context, prefs) // runs on the main thread, like Application.onCreate

        drainBackgroundWork()
        assertTrue(
            "initialize must not touch IAM tables on the main thread, but ran: $mainThreadQueries",
            mainThreadQueries.isEmpty()
        )
    }

    @Test
    fun `initialize with logging disabled never reads the campaign store for the debug summary`() {
        val allQueries = installIamQueryRecorder(mainThreadOnly = false)

        controller.initialize(context, prefs)

        drainBackgroundWork()
        // The guard is on reads, not on all activity: initialize does one piece of
        // housekeeping (sweeping acknowledged analytics events). What must stay
        // gated is the debug summary, which SELECTs every IAM table and
        // concatenates the HTML of every stored campaign.
        val reads = allQueries.filter { it.trimStart().uppercase().startsWith("SELECT") }
        assertTrue(
            "with logging disabled, initialize must not read IAM tables, but ran: $reads",
            reads.isEmpty()
        )
        assertTrue(
            "initialize must not touch the campaign table at all, but ran: $allQueries",
            allQueries.none { it.contains("iam_messages") }
        )
    }

    @Test
    fun `initialize sweeps analytics events the backend has already acknowledged`() {
        val repository = IAMRepository.getInstance(context)
        seedCampaignRow(repository, "c1")
        val acknowledged = repository.recordAnalyticsEvent("CLICK", "c1", "btn-1", "Buy", "open_url")
        val stillQueued = repository.recordAnalyticsEvent("CLICK", "c1", "btn-2", "Later", "dismiss")
        repository.markAnalyticsEventsAsSynced(listOf(acknowledged))
        awaitRepositoryCondition("the acknowledged click must leave the queue") {
            repository.getUnsyncedAnalyticsEvents(10).size == 1
        }
        // Total rows: the unsynced count reports 1 both before and after the sweep,
        // so asserting on it would pass even if initialize swept nothing.
        assertEquals(2, IAMTestDb.rowCount(context, "iam_analytics_events"))

        controller.initialize(context, prefs)
        drainBackgroundWork()

        awaitRepositoryCondition("the acknowledged click must be swept on initialize") {
            IAMTestDb.rowCount(context, "iam_analytics_events") == 1
        }
        assertEquals(
            "the click still waiting to upload must survive",
            listOf(stillQueued),
            repository.getUnsyncedAnalyticsEvents(10).map { it.id }
        )
    }

    @Test
    fun `the analytics sweep never touches the frequency-cap history`() {
        // The two tables are swept on different rules: an acknowledged click is
        // dead, an acknowledged impression is still the cap. A one_time campaign
        // whose synced display record got swept would show again.
        val repository = IAMRepository.getInstance(context)
        seedCampaignRow(repository, "c1")
        val recordId = repository.recordMessageDisplay("c1")
        repository.markRecordReported(recordId)
        awaitRepositoryCondition("impression uploaded") {
            repository.unsyncedDisplayRecords.isEmpty()
        }

        assertEquals(1, IAMTestDb.rowCount(context, "iam_display_records"))

        controller.initialize(context, prefs)
        drainBackgroundWork()

        assertEquals(
            "the synced display record must survive the analytics sweep",
            1, IAMTestDb.rowCount(context, "iam_display_records")
        )
        assertEquals(
            "an uploaded impression must still count toward the cap",
            1, repository.getDisplayCount("c1")
        )
    }

    @Test
    fun `display and queue-advance paths perform no IAM database work on the main thread`() {
        val mainThreadQueries = installIamQueryRecorder(mainThreadOnly = true)
        controller.initialize(context, prefs)
        controller.resume()
        seed(
            campaign("first", IAMTriggerCondition("custom", "sale", null), priority = 1),
            campaign("second", IAMTriggerCondition("custom", "sale", null), priority = 2)
        )

        // Trigger with NO current activity: the message stays pending in the queue.
        controller.processTrigger("sale")
        drainBackgroundWork()

        // Main-thread entry point (activity lifecycle callback) must hand the
        // DB-touching display work to the background executor.
        controller.updateCurrentActivity(activity)
        awaitDisplayed("first")

        // Queue advance after a dismissal is a main-thread callback too.
        (controller.displayManager as IAMDisplayManagerImpl).dismissCurrentMessage()
        awaitDisplayed("second")

        assertTrue(
            "display paths must not touch IAM tables on the main thread, but ran: $mainThreadQueries",
            mainThreadQueries.isEmpty()
        )
    }

    @Test
    fun `navigating to another activity carries the displayed message with it and keeps the queue alive`() {
        // The everyday version of a rotation: the user taps through to another
        // screen while a message is up. The previous activity is stopped but alive,
        // so nothing is destroyed and no configuration changes — and that is exactly
        // the case the lifecycle path used to miss. On a device the message stayed
        // parented to the screen the user had left, its WebView was rebuilt empty,
        // the SDK logged "successfully displayed", and every message after it queued
        // forever behind one nobody could see or dismiss.
        initialize()
        seed(
            campaign("first", IAMTriggerCondition("custom", "sale", null), priority = 1),
            campaign("second", IAMTriggerCondition("custom", "sale", null), priority = 2)
        )
        controller.processTrigger("sale")
        awaitDisplayed("first")
        assertTrue("precondition: message is on the first activity", hostsContainer(activity))

        // Forward navigation A→B, as IAMConfigurationManager.onActivityResumed
        // reports it.
        val secondActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.updateCurrentActivity(secondActivity)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the same message is still the one displaying", "first", displayingId())
        assertTrue(
            "the message must be hosted by the activity the user is now on",
            hostsContainer(secondActivity)
        )
        assertFalse(
            "the message must not be left behind on the activity the user left",
            hostsContainer(activity)
        )
        val webView = containerIn(secondActivity).getWebView() as android.webkit.WebView
        assertNotNull(
            "the carried-over message must still have its content",
            shadowOf(webView).lastLoadDataWithBaseURL
        )
        assertEquals(
            "carrying a message across a navigation is not a new impression",
            1, controller.repository.getDisplayCount("first")
        )

        // And the queue is still alive: dismissing advances to the next message,
        // which displays into the activity the user is on.
        (controller.displayManager as IAMDisplayManagerImpl).dismissCurrentMessage()
        awaitDisplayed("second")
        assertTrue(
            "the next message must display into the current activity",
            hostsContainer(secondActivity)
        )
    }

    private fun containerIn(host: Activity): com.pushengage.pushengage.iam.display.IAMWebViewContainer {
        val content = host.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (0 until content.childCount)
            .map { content.getChildAt(it) }
            .filterIsInstance<com.pushengage.pushengage.iam.display.IAMWebViewContainer>()
            .single()
    }

    private fun hostsContainer(host: Activity): Boolean {
        val content = host.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (0 until content.childCount).any {
            content.getChildAt(it) is com.pushengage.pushengage.iam.display.IAMWebViewContainer
        }
    }

    // ------------------------------------------------------ shutdown stops work

    @Test
    fun `a sync still in flight at shutdown does not fetch subscriber state afterwards`() {
        // shutdownNow() interrupts the sync thread's latch wait. The code read that
        // as a timeout and RAN the app-open continuation anyway — a subscriber-state
        // GET issued by a controller that had just been told to stop. It goes through
        // the static PushEngage facade, so it carries whatever prefs are installed at
        // that moment: in the suite, a later test class's hash and mock server, which
        // is how a push network test saw an IAM request it never made.
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            // Metadata reply delayed so the sync is still waiting when shutdown() lands.
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"error_code":0,"data":{"version":1,"site_id":1,"iam_status":"active","api":{}}}""")
                    .setBodyDelay(2, TimeUnit.SECONDS)
            )
            setNetworkConnected(true)
            prefs.siteKey = "site_under_test"
            prefs.hash = "hash_under_test"
            prefs.siteStatus = PEConstants.ACTIVE
            prefs.iamBaseUrl = server.url("/").toString()
            prefs.backendUrl = server.url("/").toString()
            com.pushengage.pushengage.PushEngageTestSupport.setStaticField("context", context)
            com.pushengage.pushengage.PushEngageTestSupport.setStaticField("prefs", prefs)

            controller.initialize(context, prefs) // the app-open sync starts here
            controller.shutdown()

            // Give a stray continuation every chance to fire.
            val deadline = System.currentTimeMillis() + 3_500
            val paths = mutableListOf<String>()
            while (System.currentTimeMillis() < deadline) {
                val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
                paths += request.path.orEmpty()
            }
            assertTrue(
                "a shut-down controller must not fetch subscriber state, but requested: $paths",
                paths.none { it.contains("/subscriber/") }
            )
        } finally {
            server.shutdown()
            com.pushengage.pushengage.PushEngageTestSupport.resetSingleton()
        }
    }

    private fun setNetworkConnected(connected: Boolean) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            null,
            android.net.ConnectivityManager.TYPE_WIFI,
            0,
            connected,
            if (connected) android.net.NetworkInfo.State.CONNECTED else android.net.NetworkInfo.State.DISCONNECTED
        )
        shadowOf(cm).setActiveNetworkInfo(info)
        shadowOf(cm).setNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI, info)
    }

    @Test
    fun `shutdown stops the controller's executors`() {
        // shutdown() used to leave both single-thread executors running, so work
        // queued before the call still ran afterwards and two threads stayed alive
        // for the life of the process. In the test suite that showed up as a queued
        // app-open subscriber refresh firing during an unrelated class and hitting
        // that class's MockWebServer; in an app it means a method named shutdown()
        // does not stop what it was asked to stop.
        controller.initialize(context, prefs)

        controller.shutdown()

        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        val background = IAMControllerImpl::class.java.getDeclaredField("backgroundExecutor")
            .apply { isAccessible = true }
            .get(controller) as java.util.concurrent.ExecutorService
        val sync = IAMControllerImpl::class.java.getDeclaredField("syncExecutor")
            .apply { isAccessible = true }
            .get(controller) as java.util.concurrent.ExecutorService

        assertTrue("background executor must be shut down", background.isShutdown)
        assertTrue("sync executor must be shut down", sync.isShutdown)
        // And it must refuse new work rather than silently running it later.
        try {
            background.execute { ran.set(true) }
        } catch (expected: java.util.concurrent.RejectedExecutionException) {
            // expected
        }
        assertFalse("no work may run after shutdown", ran.get())
    }

    @Test
    fun `the controller can be initialized again after a shutdown`() {
        // shutdownNow() makes the executors unusable, so initialize() replaces them.
        // Without that, every entry point that dispatches work would throw
        // RejectedExecutionException at the host app after a restart.
        controller.initialize(context, prefs)
        controller.shutdown()

        controller.initialize(context, prefs)
        controller.resume()
        seed(campaign("restarted", IAMTriggerCondition("custom", "sale", null)))
        controller.updateCurrentActivity(activity)
        controller.processTrigger("sale")
        drainBackgroundWork()

        awaitDisplayed("restarted")
    }

    // --------------------------------------------- one repository per process

    @Test
    fun `the controller uses the same repository as everything else`() {
        // It used to build its own with `new`, leaving IAMRepository.getInstance —
        // which the sync worker calls — to hand out a second object with a second
        // write executor. Two write queues over one database make
        // runAfterPendingWrites a barrier over half the writes.
        controller.initialize(context, prefs)

        assertSame(IAMRepository.getInstance(context), controller.repository)
    }

    // ------------------------------------------------- site-level feature gate

    @Test
    fun `nothing displays while IAM is switched off for the site`() {
        // iam_status off must stop display without the campaigns being deleted —
        // it is a per-site switch the backend also flips for billing and plan
        // reasons, so it has to be reversible.
        prefs.iamStatus = "inactive"
        controller.initialize(context, prefs)
        controller.resume()
        seed(campaign("gated", IAMTriggerCondition("custom", "sale", null)))
        controller.updateCurrentActivity(activity)

        controller.processTrigger("sale")
        drainBackgroundWork()

        settleAndAssertNothingDisplayed()
        assertNotNull(
            "the campaign must still be stored, ready for the site coming back on",
            controller.repository.getMessageById("gated")
        )
    }

    @Test
    fun `the same campaign displays once the site is switched back on`() {
        // Pairs with the test above: the gate is the only thing holding it back, so
        // flipping the status is enough — no re-sync, no re-download.
        prefs.iamStatus = "inactive"
        controller.initialize(context, prefs)
        controller.resume()
        seed(campaign("gated", IAMTriggerCondition("custom", "sale", null)))
        controller.updateCurrentActivity(activity)
        controller.processTrigger("sale")
        drainBackgroundWork()
        settleAndAssertNothingDisplayed()

        prefs.iamStatus = PEConstants.ACTIVE
        controller.processTrigger("sale")
        drainBackgroundWork()

        awaitDisplayed("gated")
    }

    // ----------------------------------------------- safety before initialize

    @Test
    fun `every public entry point is safe before initialize`() {
        // None of these may throw on an uninitialized controller.
        controller.processTrigger("sale")
        controller.processTrigger("sale", mapOf("k" to "v"))
        controller.saveMessages(emptyList())
        controller.saveMessages(null)
        controller.setUserAttribute("k", "v")
        controller.setUserAttribute("k", 42) // non-string: must be ignored, not crash
        controller.removeUserAttribute("k")
        controller.clearUserAttributes()
        controller.pause()
        controller.resume()
        controller.updateCurrentActivity(activity)
        controller.onActivityDestroyed(activity)
        controller.shutdown()

        assertFalse(controller.displayMessage("m1", activity))
        assertFalse(controller.hasActiveMessages())

        val latch = CountDownLatch(1)
        var syncResult = true
        controller.syncMessages { success ->
            syncResult = success
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertFalse("sync before initialize must report failure", syncResult)
    }

    @Test
    fun `initialize with null arguments does not crash`() {
        controller.initialize(null, prefs)
        controller.initialize(context, null)
        // Still functional afterwards.
        controller.initialize(context, prefs)
    }

    @Test
    fun `double initialize is idempotent`() {
        controller.initialize(context, prefs)
        controller.initialize(context, prefs)
    }

    /**
     * IAM syncs on the App ID (site_key) alone. With no App ID configured there
     * is nothing to sync against, so the request must be refused locally rather
     * than sent — and, either way, never crash. The demo app blocks an empty App
     * ID in its Settings screen, so this path is only reachable from a host app
     * that initializes the SDK without one.
     */
    @Test
    fun `sync with no App ID reports failure without crashing`() {
        for (missing in listOf("", "   ", null)) {
            prefs.siteKey = missing
            controller.initialize(context, prefs)

            val latch = CountDownLatch(1)
            var syncResult = true
            controller.syncMessages { success ->
                syncResult = success
                latch.countDown()
            }

            assertTrue("callback must fire for siteKey=<$missing>", latch.await(5, TimeUnit.SECONDS))
            assertFalse("sync without an App ID must report failure", syncResult)
        }
    }

    // -------------------------------------------------- displayMessage guards

    @Test
    fun `displayMessage rejects unknown, empty and null message ids`() {
        initialize()
        assertFalse(controller.displayMessage("no-such-message", activity))
        assertFalse(controller.displayMessage("", activity))
        assertFalse(controller.displayMessage(null, activity))
    }

    @Test
    fun `displayMessage with null activity is rejected without crashing`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))
        assertFalse(controller.displayMessage("m1", null))
    }

    // ------------------------------------------------------ end-to-end custom

    @Test
    fun `custom trigger displays the matching campaign in the current activity`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))

        controller.processTrigger("sale")

        awaitDisplayed("m1")
    }

    @Test
    fun `trigger with no matching campaign displays nothing`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))

        controller.processTrigger("unrelated_event")

        settleAndAssertNothingDisplayed()
    }

    @Test
    fun `dismissing a displayed message advances the queue to the next campaign`() {
        initialize()
        seed(
            campaign("first", IAMTriggerCondition("custom", "sale", null), priority = 1),
            campaign("second", IAMTriggerCondition("custom", "sale", null), priority = 2)
        )

        controller.processTrigger("sale")
        awaitDisplayed("first")

        (controller.displayManager as IAMDisplayManagerImpl).dismissCurrentMessage()

        awaitDisplayed("second")
    }

    @Test
    fun `impression is recorded exactly once even when displayMessage is re-driven for the on-screen message`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))

        controller.processTrigger("sale")
        awaitDisplayed("m1")
        assertEquals(1, controller.repository.getDisplayCount("m1"))

        // Rotation/activity change re-drives displayMessage for the same
        // on-screen message: must be a no-op success, not a second impression.
        assertTrue(controller.displayMessage("m1", activity))
        assertEquals(
            "re-driving the on-screen message must not double-count the impression",
            1, controller.repository.getDisplayCount("m1")
        )
    }

    @Test
    fun `paused controller ignores triggers`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))
        controller.pause()

        controller.processTrigger("sale")

        settleAndAssertNothingDisplayed()
    }

    @Test
    fun `trigger fired after the current activity is destroyed displays nothing and does not crash`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))

        controller.onActivityDestroyed(activity)
        controller.processTrigger("sale")

        settleAndAssertNothingDisplayed()
    }

    @Test
    fun `onActivityDestroyed for a different activity keeps the current one usable`() {
        initialize()
        seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))

        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityDestroyed(other)

        controller.processTrigger("sale")
        awaitDisplayed("m1")
    }

    // ------------------------------------------------- sync must not block triggers

    @Test
    fun `a trigger fired during a slow app-open sync displays without waiting for the sync`() {
        // The app-open sync's network wait must not occupy the executor that
        // processes triggers: with a slow/hung network, a trigger fired right
        // after launch used to display only after the sync resolved (up to 30s).
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            prefs.siteKey = "app-id-123"
            prefs.iamBaseUrl = server.url("/").toString()
            // Nothing enqueued: the metadata request hangs until the 30s read
            // timeout — a flaky network during app open.
            controller.initialize(context, prefs) // fires the app-open sync
            controller.resume()
            controller.updateCurrentActivity(activity)
            seed(campaign("m1", IAMTriggerCondition("custom", "sale", null)))

            controller.processTrigger("sale")

            awaitDisplayed("m1") // must display long before the sync resolves
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------- auto triggers

    @Test
    fun `resume fires app-open auto campaigns once per session`() {
        controller.initialize(context, prefs)
        seed(campaign("auto1", IAMTriggerCondition("auto", null, null)))
        controller.updateCurrentActivity(activity)

        controller.resume() // first resume of the session → app-open evaluation

        awaitDisplayed("auto1")
    }

    private fun setField(name: String, value: Any) {
        IAMControllerImpl::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(controller, value)
        }
    }

    private fun invokeProcessAutoTriggers() {
        IAMControllerImpl::class.java.getDeclaredMethod("processAutoTriggers").apply {
            isAccessible = true
            invoke(controller)
        }
    }

    /**
     * The app-open opportunity must not be burned while the app is backgrounded.
     *
     * Reproduced on device (2026-08-10, 3/3): launch the app, background it within
     * ~2 s. The app-open sync finishes a moment later, but by then `isPaused` is
     * true, so `processAutoTriggers` returned early — while `appOpenHandled` had
     * already been consumed by the first resume. Returning to the foreground never
     * re-ran it, so the campaign was silently lost for the whole process. The window
     * is as wide as the initial sync (1.4–6.9 s measured on device).
     *
     * The two sequencing details matter, so this drives the race directly rather
     * than through resume(): the evaluation must land *while paused*, and
     * `appOpenHandled` must already be spent so that resume() cannot re-run
     * `handleAppOpen()` and mask the bug.
     */
    @Test
    fun `auto evaluation that lands while paused is retried on resume`() {
        controller.initialize(context, prefs)
        seed(campaign("auto1", IAMTriggerCondition("auto", null, null)))
        controller.updateCurrentActivity(activity)

        setField("appOpenHandled", java.util.concurrent.atomic.AtomicBoolean(true))
        setField("isPaused", true)

        invokeProcessAutoTriggers()   // the sync callback lands while backgrounded
        Thread.sleep(200)
        assertNull("must not display into a backgrounded app", displayingId())

        controller.resume()           // user comes back

        awaitDisplayed("auto1")
    }

    // ----------------------------------------------------- malformed DB rows

    @Test
    fun `malformed trigger JSON stored in the database never crashes trigger processing`() {
        initialize()
        // Insert a row whose trigger_json contains the matching event substring
        // but is not valid JSON — simulating a corrupt/foreign row on disk.
        val dao = PERoomDatabase.getDatabase(context).iamDao()
        dao.insertMessages(
            listOf(
                com.pushengage.pushengage.iam.model.IAMMessage(
                    "corrupt", IAMPosition.CENTER, "<html></html>", 0L, false,
                    "{not-json", null, null, 1, null, "{not-json",
                    """{"type":"custom","event":"sale" BROKEN"""
                )
            )
        )

        // Must not crash — with parameters (exercises the JSON param filter)
        // and without.
        controller.processTrigger("sale")
        controller.processTrigger("sale", mapOf("k" to "v"))

        // Auto-trigger evaluation walks every stored row's trigger JSON.
        controller.resume()

        // Drain all async work; surviving this without an exception is the test.
        settleAndAssertNothingDisplayed()
    }

    @Test
    fun `campaign with malformed actions JSON still displays and taps do not crash`() {
        initialize()
        val dao = PERoomDatabase.getDatabase(context).iamDao()
        dao.insertMessages(
            listOf(
                com.pushengage.pushengage.iam.model.IAMMessage(
                    "bad-actions", IAMPosition.CENTER, "<html><body>x</body></html>", 0L, false,
                    "{broken json", null, null, 1, null, null,
                    """{"type":"custom","event":"sale"}"""
                )
            )
        )

        controller.processTrigger("sale")
        awaitDisplayed("bad-actions")

        // A button tap routes into the malformed actions JSON — must not crash.
        (controller.displayManager as IAMDisplayManagerImpl).handleAction("btn-1")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("message stays up after a failed action parse", "bad-actions", displayingId())
    }

    @Test
    fun `a trigger param sharing a subscriber attribute name must not destroy the stored value`() {
        // Regression guard at the controller boundary. enqueueEligibleMessages used to
        // write trigger params into the user-attribute store and then DELETE them, so a
        // param named like a subscriber attribute overwrote the real value and then
        // removed it outright — silently, since a missing attribute makes positive
        // operators fail. The engine-level tests cannot catch this: the destructive code
        // lived here, not in IAMRulesEngine.
        controller.initialize(context, prefs)
        drainBackgroundWork()

        val attributes = context.getSharedPreferences("pe_iam_user_attributes", 0)
        attributes.edit().putString("pe_user_attribute_plan", "gold").commit()

        seed(campaign("sale", IAMTriggerCondition("custom", "sale")))
        controller.processTrigger("sale", mapOf("plan" to "trial"))
        drainBackgroundWork()

        assertEquals(
            "the stored subscriber attribute must survive a trigger carrying the same key",
            "gold",
            attributes.getString("pe_user_attribute_plan", null)
        )
    }

    @Test
    fun `trigger params are never persisted to the attribute store`() {
        controller.initialize(context, prefs)
        drainBackgroundWork()

        seed(campaign("sale", IAMTriggerCondition("custom", "sale")))
        controller.processTrigger("sale", mapOf("cart_value" to "250"))
        drainBackgroundWork()

        val attributes = context.getSharedPreferences("pe_iam_user_attributes", 0)
        assertNull(
            "trigger params must stay out of the persistent store entirely",
            attributes.getString("pe_user_attribute_cart_value", null)
        )
    }

    // -------------------------------------------------- delayed campaign upkeep

    /** Advances the main looper (and Robolectric's clock) past a pending countdown. */
    private fun elapse(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
        drainBackgroundWork()
    }

    @Test
    fun `a campaign deleted during its trigger delay is not displayed when the countdown elapses`() {
        // A sync full-replaces the campaign set, so a campaign paused in the dashboard
        // can vanish mid-countdown. Releasing it would show a campaign the backend no
        // longer has.
        controller.initialize(context, prefs)
        seed(campaign("delayed", IAMTriggerCondition(type = "auto", delay = 1L)))
        controller.updateCurrentActivity(activity)
        controller.resume()  // first resume of the session → app-open evaluation
        drainBackgroundWork()
        settleAndAssertNothingDisplayed()

        seed()  // the campaign is gone

        elapse(2_000L)
        settleAndAssertNothingDisplayed()
    }

    @Test
    fun `a campaign that expired during its trigger delay is not displayed`() {
        // Eligibility is re-checked at release, not only when the countdown starts: a
        // delay window can outlive the campaign's own end date.
        controller.initialize(context, prefs)
        seed(campaign("expiring", IAMTriggerCondition(type = "auto", delay = 1L)))
        controller.updateCurrentActivity(activity)
        controller.resume()
        drainBackgroundWork()
        settleAndAssertNothingDisplayed()

        // A later sync shortens the window so it has already closed.
        seed(
            IAMMessageResponse(
                "expiring", IAMPosition.CENTER, "<html></html>", 0L, false,
                emptyMap(), null, java.util.Date(System.currentTimeMillis() - 60_000), 1,
                null, null, IAMTriggerCondition(type = "auto", delay = 1L)
            )
        )

        elapse(2_000L)
        settleAndAssertNothingDisplayed()
    }

    @Test
    fun `a delayed campaign that is still present displays when the countdown elapses`() {
        // The control: proves the two tests above are not passing merely because the
        // delay path never releases anything.
        controller.initialize(context, prefs)
        seed(campaign("still-there", IAMTriggerCondition(type = "auto", delay = 1L)))
        controller.updateCurrentActivity(activity)
        controller.resume()
        drainBackgroundWork()
        settleAndAssertNothingDisplayed()

        elapse(2_000L)
        awaitDisplayed("still-there")
    }

    @Test
    fun `a numeric subscriber attribute is stored without a decimal point`() {
        // getSubscriberDetails comes back through Gson, so the backend's `120`
        // arrives as java.lang.Double 120.0. Stored as "120.0" it could never
        // satisfy an untyped (text) audience condition authored as "120", which
        // matched on iOS.
        controller.initialize(context, prefs)
        val response = mapOf(
            "segments" to listOf("vip", 120),
            "attributes" to mapOf("cart_value" to 120.0, "rate" to 2.5, "plan" to "gold"),
            "city" to "Pune",
            "country" to "IN"
        )

        IAMControllerImpl::class.java
            .getDeclaredMethod("applySubscriberState", Any::class.java)
            .apply { isAccessible = true }
            .invoke(controller, response)

        val properties = IAMDevicePropertiesManager.getInstance(context)
        assertEquals("120", properties.getUserAttribute("attr.cart_value"))
        assertEquals("2.5", properties.getUserAttribute("attr.rate"))
        assertEquals("gold", properties.getUserAttribute("attr.plan"))
        assertEquals(setOf("vip", "120"), properties.getSegments())
    }
}
