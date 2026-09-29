package com.pushengage.pushengage.iam.controller;

import androidx.annotation.RestrictTo;

import android.app.Activity;
import android.content.Context;

import com.pushengage.pushengage.helper.PELogger;
import com.pushengage.pushengage.iam.display.IAMDisplayManager;
import com.pushengage.pushengage.iam.display.IAMDisplayManagerImpl;
import com.pushengage.pushengage.iam.model.IAMMessage;
import com.pushengage.pushengage.iam.model.IAMMessageResponse;
import com.pushengage.pushengage.iam.repository.IAMRepository;
import com.pushengage.pushengage.iam.analytics.IAMAnalyticsManager;
import com.pushengage.pushengage.iam.queue.IAMQueueManager;
import com.pushengage.pushengage.iam.queue.IAMTriggerDelayScheduler;
import com.pushengage.pushengage.iam.queue.IAMMessageListener;
import com.pushengage.pushengage.iam.rules.IAMRulesEngine;
import com.pushengage.pushengage.iam.sync.IAMSyncManager;
import com.pushengage.pushengage.iam.util.IAMConfigurationManager;
import com.pushengage.pushengage.iam.util.IAMScalarString;
import com.pushengage.pushengage.helper.PEConstants;
import com.pushengage.pushengage.helper.PEPrefs;
import com.pushengage.pushengage.PushEngage;
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the IAMController interface
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class IAMControllerImpl implements IAMController, IAMMessageListener {

    private static final String TAG = "IAMControllerImpl";

    // Everything below that initialize() assigns is volatile, because initialize()
    // runs on the main thread while these are read from backgroundExecutor,
    // syncExecutor, the WorkManager thread and whichever thread advanced the queue.
    // Without it the Java Memory Model does not promise those threads ever see the
    // write, so a background read could observe null — or, for isPaused, a stale
    // value for a cycle. The scalars that are written *after* setup were already
    // volatile (autoTriggerPassDeferred, appOpenElapsedRealtime,
    // impressionRecordedMessageId); these are the setup-time ones.
    //
    // This buys visibility, not atomicity: a check-then-act sequence over any of
    // them is still not a unit, and the guards that need that (e.g. the display
    // session) use displayLock in IAMDisplayManagerImpl.
    private volatile Context context;
    private volatile IAMRepository repository;
    private volatile IAMDisplayManager displayManager;
    // Not final: shutdown() stops these, and initialize() replaces a stopped one
    // so the SDK can be started again afterwards — which is also why they are
    // volatile, since shutdown() may be called from any thread.
    private volatile ExecutorService backgroundExecutor;
    // Network-bound work (campaign sync, analytics upload) gets its OWN lane:
    // the app-open sync can wait up to 30s on a flaky network, and parking that
    // wait on backgroundExecutor delayed every queued trigger behind it.
    private volatile ExecutorService syncExecutor;
    private volatile boolean isPaused;
    private volatile IAMQueueManager queueManager;
    private volatile IAMSyncManager syncManager;
    private volatile IAMAnalyticsManager analyticsManager;
    private volatile IAMRulesEngine rulesEngine;
    private volatile PEPrefs prefs;
    private volatile boolean isInitialized = false;
    // Weak: this controller is a process-lifetime singleton and shutdown() has
    // no reliable caller, so a strong reference would leak the last resumed
    // activity after the user backs out of it.
    private volatile java.lang.ref.WeakReference<Activity> currentActivityRef = null;
    // App-open triggers fire once per app session (this singleton lives for the
    // process). Set on the first activity resume; never re-fires within the session.
    private final java.util.concurrent.atomic.AtomicBoolean appOpenHandled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Set when the session's app-open auto-trigger pass completed while the app was
    // backgrounded and had to be skipped. `appOpenHandled` is already spent by then,
    // so without this the campaign would be lost for the rest of the process; the
    // next resume replays that same pass exactly once.
    private volatile boolean autoTriggerPassDeferred = false;
    // elapsedRealtime of the app-open instant. `trigger.delay` counts from
    // here, not from when the sync finished, so a slow sync eats into the delay
    // rather than being added on top of it. 0 = app open not seen yet.
    private volatile long appOpenElapsedRealtime = 0L;
    // Holds back app-open campaigns inside their delay window; pauses on
    // backgrounding so background time never counts toward the delay.
    private volatile IAMTriggerDelayScheduler triggerDelayScheduler;
    // Message id whose impression was recorded for the CURRENT display session.
    // Rotation/activity changes re-drive displayMessage for the same on-screen
    // message; this guard keeps it at one impression per display.
    // Cleared when the message completes.
    private volatile String impressionRecordedMessageId = null;

    // Custom-action handler registered BEFORE the SDK was initialized (common
    // in wrappers, where JS/Dart subscribes before Builder.build() runs).
    // Applied to the display manager as soon as it is created.
    private static volatile com.pushengage.pushengage.iam.action.IAMCustomActionHandler
            pendingCustomActionHandler = null;

    public static void setPendingCustomActionHandler(
            com.pushengage.pushengage.iam.action.IAMCustomActionHandler handler) {
        pendingCustomActionHandler = handler;
    }

    public IAMControllerImpl() {
        this.backgroundExecutor = Executors.newSingleThreadExecutor();
        this.syncExecutor = Executors.newSingleThreadExecutor();
        this.isPaused = true;
    }

    /**
     * Returns {@code executor}, or a replacement if it has been shut down.
     *
     * <p>Lets {@link #shutdown()} genuinely stop the queues without making the
     * controller single-use: submitting to a stopped executor throws
     * {@link java.util.concurrent.RejectedExecutionException}, which would
     * otherwise surface from a host-app entry point after a shutdown followed by
     * a fresh initialize().
     */
    private static ExecutorService liveExecutor(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) {
            return Executors.newSingleThreadExecutor();
        }
        return executor;
    }

    /**
     * Called when a message is available from the queue
     */
    @Override
    public void onMessageAvailable(IAMMessage message) {
        if (message == null) {
            PELogger.debug("onMessageAvailable: Received null message");
            return;
        }

        if (isPaused) {
            PELogger.debug("onMessageAvailable: Controller is paused, ignoring message: " + message.id);
            return;
        }

        Activity activity = currentActivityRef != null ? currentActivityRef.get() : null;
        if (activity == null) {
            PELogger.debug("onMessageAvailable: No current activity available to display message: " + message.id);
            return;
        }

        PELogger.debug("onMessageAvailable: Received message " + message.id +
                " for display in activity " + activity.getClass().getSimpleName());

        // Display the message in the current activity if we have one. The
        // queue notifies on whichever thread advanced it — including the MAIN
        // thread (dismiss animation → completion → advance) — and
        // displayMessage reads the campaign store, so always hop to the
        // background executor for the DB work (the display manager posts the
        // actual view work back to the main thread itself).
        backgroundExecutor.execute(() -> {
            // The activity was captured above; this runs later, so re-check before
            // spending a campaign read and an impression write on a screen that has
            // since gone away. The display manager checks again immediately before
            // attaching — that is the check that has to be right — but doing it here
            // too keeps a dead activity from costing an impression that was never
            // seen, which would count against the campaign's frequency cap.
            // Left as the queue's current message rather than completed, so the next
            // activity to resume re-drives it (see updateCurrentActivity). Completing
            // it here would skip a message that was never shown.
            if (IAMDisplayManagerImpl.isActivityGone(activity)) {
                PELogger.debug("onMessageAvailable: activity went away before display of " + message.id
                        + " — leaving it queued for the next screen");
                return;
            }
            boolean displayed = displayMessage(message.id, activity);
            PELogger.debug("onMessageAvailable: Message " + message.id +
                    (displayed ? " successfully displayed" : " failed to display"));
        });
    }

    @Override
    public void initialize(Context context, PEPrefs prefs) {
        if (context == null) {
            PELogger.error("Context cannot be null");
            return;
        }

        if (prefs == null) {
            PELogger.error("PEPrefs cannot be null");
            return;
        }

        if (isInitialized) {
            PELogger.debug("IAM Controller already initialized");
            return;
        }

        // A previous shutdown() stopped these; replace them so a restart works.
        this.backgroundExecutor = liveExecutor(this.backgroundExecutor);
        this.syncExecutor = liveExecutor(this.syncExecutor);

        this.prefs = prefs;
        this.context = context.getApplicationContext();
        this.repository = IAMRepository.getInstance(this.context);
        this.displayManager = new IAMDisplayManagerImpl(this.context, this.repository);
        this.displayManager.initialize();
        // Apply a custom-action listener registered before initialization
        // (wrappers subscribe from JS/Dart before Builder.build() runs).
        if (pendingCustomActionHandler != null) {
            ((IAMDisplayManagerImpl) this.displayManager)
                    .setCustomActionHandler(pendingCustomActionHandler);
        }
        this.isPaused = false;

        this.queueManager = new IAMQueueManager();
        // Released messages go straight into the queue — the delay has already been
        // served by the time onDue fires.
        this.triggerDelayScheduler = new IAMTriggerDelayScheduler(messageId -> {
            releaseDelayedMessage(messageId);
            return kotlin.Unit.INSTANCE;
        });
        this.syncManager = new IAMSyncManager(this.context, repository);
        this.analyticsManager = new IAMAnalyticsManager(this.context, repository);
        this.rulesEngine = IAMRulesEngine.create(this.context, this.repository);

        // Register as a listener for messages from the queue
        this.queueManager.addMessageListener(this);

        // IAM needs only the site_key (App ID) to fetch campaigns — not a push
        // subscription. Gate the sync on a configured App ID, NOT on siteId > 0,
        // so in-app messaging works even when notification permission was never
        // granted (siteId is populated only by the notification subscribe flow).
        String siteKey = prefs.getSiteKey();
        boolean hasAppId = siteKey != null && !siteKey.isEmpty();

        if (hasAppId) {
            // Schedule background periodic sync. The immediate sync happens on
            // app open (first activity resume) via handleAppOpen(), which syncs
            // fresh campaigns and then shows — see resume().
            syncManager.schedulePeriodicSync(siteKey);
        } else {
            PELogger.debug("IAM sync skipped: no App ID configured yet");
        }

        // Drop analytics events the backend has already acknowledged. Display
        // records are not swept: they double as the frequency-cap history.
        repository.pruneSyncedAnalyticsEvents();

        isInitialized = true;
        PELogger.debug("IAMController initialized successfully");

        // Log IAM database summary — a debugging aid only. It reads every IAM
        // table and concatenates ALL stored campaign HTML, and initialize()
        // runs on the main thread (Application.onCreate via the PushEngage
        // constructor), so it must never run unconditionally or on this
        // thread: gate it on the host's opt-in logging flag and compute it on
        // the background executor.
        if (PELogger.isLoggingEnabled()) {
            backgroundExecutor.execute(() -> {
                String databaseSummary = repository.getDatabaseSummary();
                PELogger.debug("IAM Database Summary on Initialization:\n" + databaseSummary);
            });
        }

        // App open fires HERE, at initialization — not only on the first activity
        // resume. Wrappers (React Native, Flutter) initialize the SDK lazily from
        // their JS/Dart side AFTER the host activity has already resumed, so a
        // resume-only trigger never fires there and no campaigns would ever sync.
        // Display still works in both orders: if no activity is current when the
        // synced campaigns are enqueued, the message stays pending and is shown by
        // updateCurrentActivity() as soon as an activity is known.
        if (hasAppId && appOpenHandled.compareAndSet(false, true)) {
            handleAppOpen();
        }

        // Activity lifecycle tracking starts LAST. Its initialize() replays the
        // already-resumed activity (wrappers init after it resumed) onto the main
        // thread, and that replay calls resume() — which must see a fully built
        // controller. Registered any earlier, the replay could run while this
        // method is still on the wrapper's thread: resume() would take the app-open
        // slot above with isInitialized still false, the pass would stop at
        // refreshSubscriberState, and no app-open campaign would show that session.
        // Native hosts are unaffected: they run this on the main thread in
        // Application.onCreate(), before any activity exists.
        android.app.Application app = (android.app.Application) this.context.getApplicationContext();
        IAMConfigurationManager.getInstance().initialize(app, (IAMDisplayManagerImpl) this.displayManager);
        PELogger.debug("IAM Configuration Manager initialized for automatic activity tracking");
    }

    @Override
    public void processTrigger(String triggerEvent) {
        processTrigger(triggerEvent, null);
    }

    @Override
    public void processTrigger(String triggerEvent, Map<String, String> parameters) {
        if (context == null || repository == null) {
            PELogger.error("IAM Controller not initialized");
            return;
        }

        if (isPaused) {
            PELogger.debug("IAM Controller is paused, ignoring trigger: " + triggerEvent);
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                PELogger.debug("Processing IAM trigger: " + triggerEvent);

                // Get messages for this trigger
                List<IAMMessage> messages = repository.getMessagesByTriggerEvent(triggerEvent);

                if (messages.isEmpty()) {
                    PELogger.debug("No messages found for trigger: " + triggerEvent);
                    return;
                }

                // Filter by the call's trigger parameters (if any), then run
                // eligibility + enqueue.
                // Trigger conditions are matched by the rules engine against the
                // parameters this occurrence carried. Always applied — a campaign with
                // conditions must satisfy them even when the call passes nothing.
                List<IAMMessage> matched = new ArrayList<>();
                for (IAMMessage candidate : messages) {
                    if (rulesEngine.matchesTriggerConditions(candidate, parameters)) {
                        matched.add(candidate);
                    }
                }
                messages = matched;
                enqueueEligibleMessages(messages, parameters, triggerEvent);
            } catch (Exception e) {
                PELogger.error("Error processing IAM trigger: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Whether in-app messaging is switched on for this site.
     *
     * <p>Mirrors the {@code iam_status} field of the metadata document, which the
     * backend collapses several server-side checks into (site status, payment,
     * plan). It is a feature gate, not a campaign gate: when it is off the stored
     * campaigns stay exactly as they are and simply do not display, so switching
     * the site back on restores messaging immediately with its frequency-cap
     * history intact and without re-downloading anything.
     *
     * <p>Empty until the first successful metadata sync, which correctly means
     * "do not display" — there are no campaigns to display at that point anyway.
     */
    private boolean isIamEnabledForSite() {
        return prefs != null && PEConstants.ACTIVE.equalsIgnoreCase(prefs.getIamStatus());
    }

    /**
     * Runs the rules engine over {@code messages} and enqueues the eligible ones.
     * Shared by custom triggers ({@link #processTrigger}) and auto triggers
     * ({@link #processAutoTriggers}). When {@code parameters} are present they are
     * passed to the rules engine for that evaluation only, so audience conditions
     * can match against them without touching the stored user attributes.
     * {@code label} is used only for log context.
     *
     * <p>Runs on the caller's thread — callers invoke it from
     * {@link #backgroundExecutor}.
     */
    private void enqueueEligibleMessages(List<IAMMessage> messages, Map<String, String> parameters, String label) {
        enqueueEligibleMessages(messages, parameters, label, false);
    }

    /**
     * As {@link #enqueueEligibleMessages(List, Map, String)}, but when
     * {@code applyTriggerDelay} is true an eligible campaign declaring
     * {@code trigger.delay} is held back by the remaining delay instead of
     * being queued straight away.
     *
     * <p>Only the auto (app-open) path passes true — {@code delay} is
     * deliberately ignored for custom triggers.
     */
    private void enqueueEligibleMessages(List<IAMMessage> messages, Map<String, String> parameters,
                                         String label, boolean applyTriggerDelay) {
        // Site-level feature gate. Both trigger paths funnel through here, so this is
        // the one place that has to ask. Checked rather than enforced by deleting the
        // stored campaigns: iam_status is a per-site switch that the backend also
        // flips for billing and plan reasons, so it is expected to go off and on
        // again, and data deleted on the way down does not come back on the way up.
        if (!isIamEnabledForSite()) {
            PELogger.debug("IAM is not active for this site — not enqueueing for: " + label);
            return;
        }

        // Audience conditions read subscriber/device data only — trigger parameters
        // are matched separately by the trigger's own conditions, so the two
        // vocabularies stay distinct. Parameters are never written into the
        // user-attribute store either: that store also holds subscriber attributes, and
        // the previous set-then-remove approach overwrote a same-named attribute and
        // then deleted it outright.
        List<IAMMessage> eligibleMessages = new ArrayList<>();
        for (IAMMessage message : messages) {
            // Defer, don't decide: a campaign targeting subscriber-backed data we have
            // never fetched is skipped for this pass rather than evaluated. Evaluating
            // would be wrong both ways — a positive operator would hide it from every
            // new install, and a negative one would show it to everyone.
            if (rulesEngine.requiresUnavailableSubscriberState(message)) {
                PELogger.debug("Deferring message " + message.id
                        + ": audience needs subscriber data that has not been fetched yet");
                continue;
            }
            if (rulesEngine.isEligibleForDisplay(message)) {
                eligibleMessages.add(message);
            }
        }

        PELogger.debug("Found " + eligibleMessages.size() + " eligible messages for: " + label);
        for (IAMMessage message : eligibleMessages) {
            PELogger.debug("Eligible message: " + message.id + ", priority: " + message.priority);
        }

        if (queueManager == null || eligibleMessages.isEmpty()) {
            return;
        }

        if (!applyTriggerDelay) {
            int added = queueManager.addMessages(eligibleMessages);
            PELogger.debug("Added " + added + " messages to the queue");
            return;
        }

        // App-open path: hold back anything still inside its delay window. The
        // delay is measured from app open, so a slow sync has already eaten into
        // it — a campaign whose window elapsed during the sync queues immediately.
        List<IAMMessage> immediate = new ArrayList<>();
        for (IAMMessage message : eligibleMessages) {
            long remaining = remainingTriggerDelayMillis(message);
            if (remaining > 0 && triggerDelayScheduler != null) {
                triggerDelayScheduler.schedule(message.id, remaining);
            } else {
                immediate.add(message);
            }
        }

        if (!immediate.isEmpty()) {
            int added = queueManager.addMessages(immediate);
            PELogger.debug("Added " + added + " messages to the queue");
        }
    }

    /**
     * Enqueues a campaign whose {@code trigger.delay} has been served.
     *
     * <p>The campaign is re-read rather than carried through the countdown: a sync
     * full-replaces the campaign set, so it may have been deleted — and its dates or
     * frequency cap may have lapsed — while the delay ran.
     */
    private void releaseDelayedMessage(String messageId) {
        if (repository == null || queueManager == null || rulesEngine == null) {
            return;
        }

        IAMMessage message = repository.getMessageById(messageId);
        if (message == null) {
            PELogger.debug("IAM delay: message " + messageId + " no longer exists — not queueing");
            return;
        }

        if (rulesEngine.requiresUnavailableSubscriberState(message)
                || !rulesEngine.isEligibleForDisplay(message)) {
            PELogger.debug("IAM delay: message " + messageId + " no longer eligible — not queueing");
            return;
        }

        queueManager.addMessage(message);
    }

    /**
     * Milliseconds still to wait before {@code message} may be shown, or {@code 0}
     * when it has no delay or the window already elapsed.
     *
     * <p>Measured from the app-open instant ({@link #appOpenElapsedRealtime}) rather
     * than from now, so time spent in the app-open sync counts toward the delay.
     */
    private long remainingTriggerDelayMillis(IAMMessage message) {
        // Wire field is `delay` and its unit is SECONDS (unitless name, matching
        // frequency.interval / displayDuration). Kept in a *Seconds local so the
        // conversion below reads unambiguously.
        long delaySeconds;
        try {
            delaySeconds = new JSONObject(message.triggerJson).optLong("delay", 0L);
        } catch (JSONException e) {
            PELogger.error("Error reading trigger delay for message " + message.id + ": " + e.getMessage());
            return 0L;
        }

        if (delaySeconds <= 0) {
            return 0L;
        }

        long origin = appOpenElapsedRealtime;
        if (origin <= 0) {
            // No recorded app-open instant (e.g. auto triggers evaluated outside the
            // app-open path) — fall back to delaying from now.
            origin = SystemClock.elapsedRealtime();
        }

        long dueAt = origin + (delaySeconds * 1000L);
        return Math.max(0L, dueAt - SystemClock.elapsedRealtime());
    }

    /**
     * Displays a specific message in the given activity
     * 
     * @param messageId Message ID to display
     * @param activity  Activity to display in
     * @return true if displayed, false otherwise
     */
    @Override
    public boolean displayMessage(String messageId, Activity activity) {
        if (context == null || repository == null || displayManager == null) {
            PELogger.error("IAM Controller not fully initialized");
            return false;
        }

        if (messageId == null || messageId.isEmpty()) {
            PELogger.error("Invalid message ID");
            return false;
        }

        if (activity == null) {
            PELogger.error("Cannot display message in null activity");
            return false;
        }

        PELogger.debug("Preparing to display message: " + messageId);

        // A rotation/activity change re-drives this method for the message that
        // is ALREADY on screen (updateCurrentActivity re-displays the queue's
        // current message). Make sure the container is hosted by the activity
        // the user is now on — a rotation has destroyed the old host, a forward
        // navigation has merely stopped it, and both need the move — then treat
        // it as a no-op success: reporting failure here falsely completed the
        // message and cascaded the queue (one rotation consumed every message).
        // Checked BEFORE the repository read: this is the path driven from
        // main-thread lifecycle callbacks, and it needs no database access.
        if (displayManager instanceof IAMDisplayManagerImpl
                && messageId.equals(((IAMDisplayManagerImpl) displayManager).getDisplayingMessageId())) {
            PELogger.debug("Message " + messageId + " already on screen — re-attach if needed, no re-count");
            ((IAMDisplayManagerImpl) displayManager).reattachIfNotHostedBy(activity);
            return true;
        }

        // Fetch the message from repository
        IAMMessage message = repository.getMessageById(messageId);
        if (message == null) {
            PELogger.error("Message not found: " + messageId);
            return false;
        }

        // ONE impression per display-session of a message:
        // a rotation or activity change re-drives this method for the message
        // already on screen (updateCurrentActivity), and the old isDisplaying
        // guard failed right after a relaunch — re-counting the impression.
        // Guard on the message id instead; cleared when the message completes,
        // so a genuine later re-display (e.g. recurring on next app open)
        // records a fresh impression.
        long impressionId = -1;
        if (!messageId.equals(impressionRecordedMessageId)) {
            PELogger.debug("Recording impression for message: " + messageId);
            impressionId = recordMessageImpression(messageId);
            impressionRecordedMessageId = messageId;

            // Set the impression ID in the display manager
            if (displayManager instanceof IAMDisplayManagerImpl) {
                ((IAMDisplayManagerImpl) displayManager).setCurrentDisplayRecordId(impressionId);
            }
        } else {
            PELogger.debug("Skipping impression recording - already recorded for this display session");
        }

        // Wire the completion listener BEFORE the display attempt so it is in
        // place for both outcomes: user dismissal, and a scheduled (posted to
        // main thread) display that fails. Either way the queue advances to the
        // next message exactly once.
        if (displayManager instanceof IAMDisplayManagerImpl) {
            ((IAMDisplayManagerImpl) displayManager).setMessageCompletionListener(() -> {
                PELogger.debug("Message " + messageId + " dismissed or completed, processing next message");
                impressionRecordedMessageId = null;
                if (queueManager != null) {
                    queueManager.markCurrentMessageComplete();
                }
            });
        }

        // Display the message. true = displayed or scheduled on the main thread;
        // false = rejected up-front (paused / already displaying / null message).
        boolean displayed = displayManager.displayMessage(message, activity);

        if (!displayed) {
            PELogger.debug("Failed to display message: " + messageId + ", marking as complete");
            if (queueManager != null) {
                queueManager.markCurrentMessageComplete();
            }
        } else {
            PELogger.debug("Message accepted for display: " + messageId);
        }

        return displayed;
    }

    // filterMessagesByParameters removed: trigger matching now goes through
    // IAMRulesEngine.matchesTriggerConditions, which supports operators, declared
    // types and any/all, and treats campaign conditions as requirements rather than
    // requiring every app-supplied parameter to be declared.


    @Override
    public void saveMessages(List<IAMMessageResponse> messages) {
        if (context == null || repository == null) {
            PELogger.error("IAM Controller not initialized");
            return;
        }

        if (messages == null || messages.isEmpty()) {
            PELogger.debug("No messages to save");
            return;
        }

        PELogger.debug("Saving " + messages.size() + " IAM messages");
        repository.saveMessages(messages);
    }

    @Override
    public boolean hasActiveMessages() {
        if (context == null || repository == null) {
            PELogger.error("IAM Controller not initialized");
            return false;
        }

        List<IAMMessage> activeMessages = repository.getValidMessages();
        return activeMessages != null && !activeMessages.isEmpty();
    }

    @Override
    public void syncMessages(SyncCallback callback) {
        if (syncManager == null || repository == null) {
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }

        // Gate on the App ID (site_key), not siteId — IAM syncs with the App ID
        // alone and must work without a push subscription.
        String siteKey = prefs.getSiteKey();
        if (siteKey == null || siteKey.isEmpty()) {
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }

        syncExecutor.execute(() -> {
            try {
                PELogger.debug("Syncing IAM messages for App ID: " + siteKey);

                // Create CountDownLatch to wait for sync completion
                CountDownLatch syncLatch = new CountDownLatch(1);
                final boolean[] result = { false }; // Array to hold the result

                syncManager.syncMessages(siteKey, new IAMSyncManager.SyncCallback() {
                    @Override
                    public void onComplete(boolean success) {
                        result[0] = success;
                        syncLatch.countDown();
                    }
                });

                // Wait for sync to complete with timeout
                boolean completed = false;
                try {
                    completed = syncLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    PELogger.error("Sync interrupted: " + e.getMessage(), e);
                }

                // Handle timeout or completion
                if (!completed) {
                    PELogger.error("Sync timed out after 30 seconds");
                    if (callback != null) {
                        callback.onComplete(false);
                    }
                    return;
                }

                PELogger.debug("Sync completed with result: " + result[0]);

                // Report unreported analytics if sync was successful
                if (result[0]) {
                    syncAnalytics();
                }

                if (callback != null) {
                    callback.onComplete(result[0]);
                }
            } catch (Exception e) {
                PELogger.error("Error during sync: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onComplete(false);
                }
            }
        });
    }

    @Override
    public void syncAnalytics() {
        if (analyticsManager != null) {
            // Network-bound: the drain POSTs synchronously, so keep it off the
            // trigger/display executor.
            syncExecutor.execute(() -> {
                analyticsManager.forceSyncAnalytics();
            });
        }
    }

    @Override
    public boolean isPaused() {
        return isPaused;
    }

    @Override
    public void pause() {
        PELogger.debug("Pausing IAM Controller");
        isPaused = true;

        // Pause queue processing when app goes to background
        if (queueManager != null) {
            queueManager.pause();
        }

        // Freeze any pending app-open delay: background time must not count
        // toward it.
        if (triggerDelayScheduler != null) {
            triggerDelayScheduler.pause();
        }
    }

    @Override
    public void resume() {
        PELogger.debug("Resuming IAM Controller");
        isPaused = false;

        // Resume queue processing when app comes to foreground
        if (queueManager != null) {
            queueManager.resume();
        }

        // Continue any pending app-open delay from the time that was left.
        if (triggerDelayScheduler != null) {
            triggerDelayScheduler.resume();
        }

        // Sweep acknowledged analytics events on resume too, so a long-lived
        // install does not carry uploaded clicks between cold starts.
        if (repository != null) {
            repository.pruneSyncedAnalyticsEvents();
        }

        // App-open trigger: fire ONCE per app session — the first time an activity
        // resumes in this process. Subsequent activity resumes / foreground returns
        // within the same session do NOT re-fire (so it shows when the app opens,
        // not on every screen navigation).
        if (appOpenHandled.compareAndSet(false, true)) {
            handleAppOpen();
        } else if (autoTriggerPassDeferred) {
            // The session's app-open pass completed while the app was in the
            // background and was skipped. This is still that same pass, not a
            // second one, so it does not re-fire app-open on every return.
            autoTriggerPassDeferred = false;
            PELogger.debug("Replaying the app-open auto-trigger pass deferred while backgrounded");
            processAutoTriggers();
        }
    }

    /**
     * Handles "app open" (contract: show automatically when the app opens, once
     * per session). Fetches fresh campaigns first, then evaluates auto-triggers;
     * if the fetch fails, falls back to the campaigns already stored locally.
     * The sync callback fires after the local store is updated, so auto-triggers
     * always see the freshest available data.
     */
    private void handleAppOpen() {
        // Stamp the app-open instant BEFORE the sync: trigger.delay counts
        // from here, so sync time is absorbed by the delay instead of added to it.
        appOpenElapsedRealtime = SystemClock.elapsedRealtime();
        PELogger.debug("App open — syncing fresh campaigns before evaluating auto-triggers");
        syncMessages(success -> {
            PELogger.debug("App-open sync " + (success ? "succeeded — evaluating on fresh data"
                    : "failed — falling back to existing data"));
            // Subscriber-backed audience data (segments, attributes, geo) is refreshed
            // BEFORE evaluating, so segment-targeted campaigns can match on this pass.
            // A failure is not fatal: the cached snapshot is used, and campaigns needing
            // state we have never fetched are deferred rather than mis-evaluated.
            refreshSubscriberState(() -> processAutoTriggers());
        });
    }

    /**
     * Fetches the subscriber-backed audience fields and caches them, then runs
     * {@code next} regardless of the outcome.
     *
     * <p>Requests <strong>only</strong> the fields audience targeting needs, and always
     * passes the field list explicitly rather than relying on any server-side default.
     * Nothing beyond these fields should reach the device or the logs.
     */
    private void refreshSubscriberState(Runnable next) {
        // A controller that has been shut down issues no network calls and does not
        // continue the app-open pass. This IS reachable after shutdown: shutdownNow()
        // interrupts the sync thread's latch wait in syncMessages, which is reported
        // as a timeout and then completes the callback that lands here. The fetch
        // below goes through the static PushEngage facade, so it would carry whatever
        // prefs are installed at that moment — a request nobody asked for, under an
        // identity that need not even be this controller's.
        if (!isInitialized) {
            PELogger.debug("IAM: controller shut down — skipping subscriber-state fetch");
            return;
        }

        // Before anything is read: a snapshot cached under a previous subscriber must
        // not answer for this one, and only a successful fetch would otherwise replace
        // it. Runs even with no hash, so unsubscribing discards it too.
        if (rulesEngine != null) {
            String identity = prefs != null && prefs.getHash() != null ? prefs.getHash() : "";
            rulesEngine.invalidateSubscriberStateIfIdentityChanged(identity);
        }

        if (prefs == null || prefs.getHash() == null || prefs.getHash().isEmpty()) {
            // No subscriber yet (no FCM token, or subscribe has not completed). Nothing
            // to fetch; audience conditions on subscriber-backed fields stay deferred.
            PELogger.debug("IAM: no subscriber hash yet — skipping subscriber-state fetch");
            next.run();
            return;
        }

        List<String> fields = Arrays.asList(
                "segments", "attributes", "city", "state", "country", "has_unsubscribed");

        PushEngage.getSubscriberDetails(fields, new PushEngageResponseCallback() {
            @Override
            public void onSuccess(Object response) {
                try {
                    applySubscriberState(response);
                } catch (Exception e) {
                    PELogger.error("IAM: could not apply subscriber state: " + e.getMessage(), e);
                }
                next.run();
            }

            @Override
            public void onFailure(Integer errorCode, String message) {
                PELogger.error("IAM: subscriber-state fetch failed (" + errorCode + " " + message
                        + ") — evaluating against the cached snapshot");
                next.run();
            }
        });
    }

    /** Maps the subscriber-details payload onto the audience field names. */
    private void applySubscriberState(Object response) {
        if (!(response instanceof Map)) {
            PELogger.debug("IAM: unexpected subscriber-details payload — ignoring");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response;

        Set<String> segments = new HashSet<>();
        Object rawSegments = data.get("segments");
        if (rawSegments instanceof List) {
            for (Object segment : (List<?>) rawSegments) {
                if (segment != null) {
                    segments.add(IAMScalarString.render(segment));
                }
            }
        }

        // The response comes back through Gson, so a backend `120` is a Double
        // 120.0. IAMScalarString spells it "120" — the text an untyped audience
        // condition compares against, and what iOS stores for the same value.
        Map<String, String> attributes = new HashMap<>();
        Object rawAttributes = data.get("attributes");
        if (rawAttributes instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawAttributes).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    attributes.put(entry.getKey().toString(), IAMScalarString.render(entry.getValue()));
                }
            }
        }

        // The backend calls the IP-derived value `country`; the audience field is
        // `geo_country`, kept distinct from the device locale's `device_region`.
        Map<String, String> scalars = new HashMap<>();
        putIfPresent(scalars, "city", data.get("city"));
        putIfPresent(scalars, "state", data.get("state"));
        putIfPresent(scalars, "geo_country", data.get("country"));
        Object hasUnsubscribed = data.get("has_unsubscribed");
        if (hasUnsubscribed instanceof Number) {
            // Numeric 0/1 on the wire; the audience field is a boolean.
            scalars.put("has_unsubscribed",
                    ((Number) hasUnsubscribed).intValue() != 0 ? "true" : "false");
        }

        String identity = prefs != null && prefs.getHash() != null ? prefs.getHash() : "";
        rulesEngine.applySubscriberState(segments, attributes, scalars, identity);
    }

    private void putIfPresent(Map<String, String> target, String key, Object value) {
        if (value != null && !value.toString().isEmpty()) {
            target.put(key, IAMScalarString.render(value));
        }
    }

    /**
     * Processes all messages with auto triggers
     * Called when app comes to foreground to handle app_open-like events
     */
    private void processAutoTriggers() {
        if (repository == null) {
            return;
        }

        // The app-open sync is asynchronous, so this callback can land after the
        // user has already left the app. Dropping it here used to lose the
        // campaign for the whole process: `appOpenHandled` has already been spent
        // by the resume that started the sync, so nothing re-ran the auto pass on
        // the way back. Remember the missed pass instead and retry it in resume().
        if (isPaused) {
            PELogger.debug("Auto-trigger pass arrived while backgrounded — deferring to resume");
            autoTriggerPassDeferred = true;
            return;
        }
        autoTriggerPassDeferred = false;

        backgroundExecutor.execute(() -> {
            try {
                PELogger.debug("Processing auto triggers for in-app messages");

                // Collect the auto-triggered messages. Auto triggers are selected by
                // type alone — they carry no event to match,
                // so they are enqueued directly rather than routed back through
                // processTrigger()'s event lookup (which would drop an eventless
                // auto campaign). Matches the iOS SDK, which keys the auto path off
                // trigger.type.
                List<IAMMessage> autoMessages = new ArrayList<>();
                for (IAMMessage message : repository.getAllActiveMessages()) {
                    try {
                        JSONObject triggerJson = new JSONObject(message.triggerJson);
                        if ("auto".equals(triggerJson.optString("type"))) {
                            PELogger.debug("Found auto trigger message: " + message.id);
                            autoMessages.add(message);
                        }
                    } catch (JSONException e) {
                        PELogger.error("Error parsing trigger JSON for message ID " +
                                message.id + ": " + e.getMessage());
                    }
                }

                if (!autoMessages.isEmpty()) {
                    // true = honor trigger.delay; the app-open path is the
                    // only one that does.
                    enqueueEligibleMessages(autoMessages, null, "auto", true);
                }
            } catch (Exception e) {
                PELogger.error("Error processing auto triggers: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Shuts down the IAM controller
     * Called when the SDK is being shut down
     */
    @Override
    public void shutdown() {
        PELogger.debug("Shutting down IAM Controller");
        isPaused = true;

        // Stop the queues. Previously neither executor was ever shut down, so work
        // queued before the call still ran afterwards — a method named shutdown()
        // that did not stop the work it was asked to stop, and two threads left
        // alive for the life of the process. A queued app-open subscriber refresh
        // running after shutdown is the visible version of that.
        //
        // shutdownNow(), not shutdown(): pending display and sync work is stale by
        // definition once IAM has been told to stop, so it should be dropped rather
        // than drained. initialize() replaces a stopped executor, so this does not
        // make the controller single-use.
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
        if (syncExecutor != null) {
            syncExecutor.shutdownNow();
        }

        // Clean up IAMConfigurationManager
        if (context != null) {
            android.app.Application app = (android.app.Application) context.getApplicationContext();
            IAMConfigurationManager.getInstance().cleanup(app);
            PELogger.debug("IAM Configuration Manager cleaned up");
        }

        // Clean up new components
        if (analyticsManager != null) {
            analyticsManager.shutdown();
        }

        if (syncManager != null) {
            syncManager.cancelPeriodicSync();
        }

        if (queueManager != null) {
            queueManager.removeMessageListener(this);
            queueManager.clearQueue();
        }

        if (displayManager != null) {
            displayManager.shutdown();
        }

        currentActivityRef = null;
        // The controller is no longer initialized, so initialize() must be allowed
        // to run again — it is what replaces the executors stopped above. Leaving
        // this true made the guard at the top of initialize() return early, so a
        // restart left the stopped executors in place and every entry point that
        // dispatched work threw RejectedExecutionException at the host app.
        isInitialized = false;
    }

    /**
     * Records analytics for a message impression
     * 
     * @param messageId ID of the message
     * @return Record ID for updating later
     */
    private long recordMessageImpression(String messageId) {
        if (analyticsManager != null) {
            return analyticsManager.recordImpression(messageId);
        }
        return -1;
    }

    /**
     * Sets a user attribute for audience targeting
     * 
     * @param key   Property key
     * @param value Property value
     */
    public void setUserAttribute(String key, Object value) {
        if (rulesEngine != null && value instanceof String) {
            rulesEngine.setUserAttribute(key, (String) value);
        }
    }

    /**
     * Removes a user attribute
     * 
     * @param key Property key to remove
     */
    public void removeUserAttribute(String key) {
        if (rulesEngine != null) {
            rulesEngine.removeUserAttribute(key);
        }
    }

    /**
     * Clears all user attributes
     */
    @Override
    public void clearUserAttributes() {
        if (rulesEngine != null) {
            rulesEngine.clearUserAttributes();
        }
    }

    /**
     * Gets the display manager instance
     * 
     * @return The IAMDisplayManager instance
     */
    public IAMDisplayManager getDisplayManager() {
        return displayManager;
    }

    /**
     * Gets the repository used by this controller
     * 
     * @return The IAM repository
     */
    public IAMRepository getRepository() {
        return repository;
    }

    /**
     * Internal method called by IAMConfigurationManager when an activity is
     * destroyed. Drops the current-activity reference if it points at the
     * destroyed instance so no display path can target a dead activity.
     *
     * @param activity The destroyed activity
     */
    public void onActivityDestroyed(Activity activity) {
        Activity current = currentActivityRef != null ? currentActivityRef.get() : null;
        if (current == activity) {
            currentActivityRef = null;
        }
    }

    /**
     * Internal method called by IAMConfigurationManager to update the current
     * activity
     *
     * @param activity The new current activity
     */
    public void updateCurrentActivity(Activity activity) {
        if (activity == null) {
            PELogger.debug("updateCurrentActivity: null activity provided");
            return;
        }

        PELogger.debug("updateCurrentActivity: Setting current activity to " + activity.getClass().getSimpleName());
        this.currentActivityRef = new java.lang.ref.WeakReference<>(activity);

        // Update the display manager with new activity context
        if (displayManager instanceof IAMDisplayManagerImpl) {
            ((IAMDisplayManagerImpl) displayManager).updateContext(activity);
        }

        // If we have a current message from the queue, try to display it
        if (queueManager != null && !isPaused) {
            IAMMessage currentQueueMessage = queueManager.getCurrentMessage();
            if (currentQueueMessage != null) {
                PELogger.debug("updateCurrentActivity: Found pending message " + currentQueueMessage.id +
                        " to display in new activity");
                // This is a main-thread lifecycle callback. The on-screen
                // re-drive (rotation/navigation) is pure view work and must
                // stay on this thread; a genuinely pending message needs
                // repository reads + an impression write, so that display runs
                // on the background executor (the display manager posts its
                // view work back to the main thread itself).
                boolean onScreenRedrive = displayManager instanceof IAMDisplayManagerImpl
                        && currentQueueMessage.id.equals(
                                ((IAMDisplayManagerImpl) displayManager).getDisplayingMessageId());
                if (onScreenRedrive) {
                    boolean displayed = displayMessage(currentQueueMessage.id, activity);
                    PELogger.debug("updateCurrentActivity: Pending message " + currentQueueMessage.id +
                            (displayed ? " successfully displayed" : " failed to display"));
                } else {
                    backgroundExecutor.execute(() -> {
                        boolean displayed = displayMessage(currentQueueMessage.id, activity);
                        PELogger.debug("updateCurrentActivity: Pending message " + currentQueueMessage.id +
                                (displayed ? " successfully displayed" : " failed to display"));
                    });
                }
            } else {
                PELogger.debug("updateCurrentActivity: No pending message to display");
            }
        }
    }
}