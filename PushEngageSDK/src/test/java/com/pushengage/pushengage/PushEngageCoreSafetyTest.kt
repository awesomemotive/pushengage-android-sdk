package com.pushengage.pushengage

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.pushengage.pushengage.Callbacks.PushEngageResponseCallback
import com.pushengage.pushengage.helper.PEPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushEngageCoreSafetyTest {

    private lateinit var context: Context
    private var originalContext: Any? = null
    private var originalPrefs: Any? = null
    private var originalInProgress: Any? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("PushEngage", Context.MODE_PRIVATE).edit().clear().commit()

        originalContext = getStaticField("context")
        originalPrefs = getStaticField("prefs")
        originalInProgress = getStaticField("isFirebaseApiCallInProgress")
    }

    @After
    fun tearDown() {
        setStaticField("context", originalContext)
        setStaticField("prefs", originalPrefs)
        setStaticField("isFirebaseApiCallInProgress", originalInProgress)
    }

    @Test
    fun getNotificationPermissionStatus_whenContextNull_returnsDenied() {
        setStaticField("context", null)

        val status = PushEngage.getNotificationPermissionStatus()

        assertEquals("denied", status)
    }

    @Test
    fun getSubscriptionStatus_whenSdkNotInitialized_returnsFailure() {
        setStaticField("context", null)
        setStaticField("prefs", null)

        var failedMessage: String? = null
        PushEngage.getSubscriptionStatus(object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}

            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failedMessage = errorMessage
            }
        })

        assertEquals("SDK not initialized", failedMessage)
    }

    @Test
    fun unsubscribe_whenSdkNotInitialized_returnsFailure() {
        setStaticField("context", null)

        var failedMessage: String? = null
        PushEngage.unsubscribe(object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}

            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failedMessage = errorMessage
            }
        })

        assertEquals("SDK not initialized", failedMessage)
    }

    @Test
    fun subscribeWithActivity_whenSdkNotInitialized_returnsFailure() {
        setStaticField("context", null)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        var failedMessage: String? = null
        PushEngage.subscribe(activity, object : PushEngageResponseCallback {
            override fun onSuccess(responseObject: Any?) {}

            override fun onFailure(errorCode: Int?, errorMessage: String?) {
                failedMessage = errorMessage
            }
        })

        assertEquals("SDK not initialized", failedMessage)
    }

    @Test
    fun subscribe_whenTokenFetchAlreadyInProgress_skipsSecondGetTokenCall() {
        setStaticField("context", context)
        setStaticField("prefs", PEPrefs(context))
        setStaticField("isFirebaseApiCallInProgress", false)

        val firebaseAppStatic: MockedStatic<FirebaseApp> = Mockito.mockStatic(FirebaseApp::class.java)
        val firebaseMessagingStatic: MockedStatic<FirebaseMessaging> = Mockito.mockStatic(FirebaseMessaging::class.java)
        try {
            val firebaseApp = Mockito.mock(FirebaseApp::class.java)
            val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
            val tokenTask = Mockito.mock(Task::class.java) as Task<String>

            firebaseAppStatic.`when`<FirebaseApp> { FirebaseApp.initializeApp(context) }.thenReturn(firebaseApp)
            firebaseMessagingStatic.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }
                .thenReturn(firebaseMessaging)

            Mockito.doNothing().`when`(firebaseMessaging).setAutoInitEnabled(true)
            whenever(firebaseMessaging.getToken()).thenReturn(tokenTask)
            whenever(tokenTask.addOnCompleteListener(any())).thenReturn(tokenTask)
            whenever(tokenTask.addOnFailureListener(any())).thenReturn(tokenTask)

            PushEngage.subscribe()
            PushEngage.subscribe()

            Mockito.verify(firebaseMessaging, Mockito.times(1)).getToken()
            assertTrue(getStaticField("isFirebaseApiCallInProgress") as Boolean)
        } finally {
            firebaseMessagingStatic.close()
            firebaseAppStatic.close()
        }
    }

    @Test
    fun subscribe_whenTokenTaskCompletesWithFailure_resetsInProgressFlag() {
        setStaticField("context", context)
        setStaticField("prefs", PEPrefs(context))
        setStaticField("isFirebaseApiCallInProgress", false)

        val firebaseAppStatic: MockedStatic<FirebaseApp> = Mockito.mockStatic(FirebaseApp::class.java)
        val firebaseMessagingStatic: MockedStatic<FirebaseMessaging> = Mockito.mockStatic(FirebaseMessaging::class.java)
        try {
            val firebaseApp = Mockito.mock(FirebaseApp::class.java)
            val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
            val tokenTask = Mockito.mock(Task::class.java) as Task<String>
            val completedTask = Mockito.mock(Task::class.java) as Task<String>

            firebaseAppStatic.`when`<FirebaseApp> { FirebaseApp.initializeApp(context) }.thenReturn(firebaseApp)
            firebaseMessagingStatic.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }
                .thenReturn(firebaseMessaging)

            Mockito.doNothing().`when`(firebaseMessaging).setAutoInitEnabled(true)
            whenever(firebaseMessaging.getToken()).thenReturn(tokenTask)
            whenever(completedTask.isSuccessful).thenReturn(false)
            whenever(completedTask.exception).thenReturn(RuntimeException("token failure"))

            whenever(tokenTask.addOnCompleteListener(any())).thenAnswer { invocation ->
                val listener = invocation.arguments[0] as OnCompleteListener<String>
                listener.onComplete(completedTask)
                tokenTask
            }
            whenever(tokenTask.addOnFailureListener(any())).thenReturn(tokenTask)

            PushEngage.subscribe()

            assertEquals(false, getStaticField("isFirebaseApiCallInProgress"))
        } finally {
            firebaseMessagingStatic.close()
            firebaseAppStatic.close()
        }
    }

    @Test
    fun subscribe_whenFirebaseTaskIsNull_throwsAndStateRemainsAccessible() {
        setStaticField("context", context)
        setStaticField("prefs", PEPrefs(context))
        setStaticField("isFirebaseApiCallInProgress", false)

        val firebaseAppStatic: MockedStatic<FirebaseApp> = Mockito.mockStatic(FirebaseApp::class.java)
        val firebaseMessagingStatic: MockedStatic<FirebaseMessaging> = Mockito.mockStatic(FirebaseMessaging::class.java)
        try {
            val firebaseApp = Mockito.mock(FirebaseApp::class.java)
            val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)

            firebaseAppStatic.`when`<FirebaseApp> { FirebaseApp.initializeApp(context) }.thenReturn(firebaseApp)
            firebaseMessagingStatic.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }
                .thenReturn(firebaseMessaging)
            Mockito.doNothing().`when`(firebaseMessaging).setAutoInitEnabled(true)
            whenever(firebaseMessaging.getToken()).thenReturn(null)

            var thrown: Throwable? = null
            try {
                PushEngage.subscribe()
            } catch (t: Throwable) {
                thrown = t
            }

            assertNotNull(thrown)
            val state = getStaticField("isFirebaseApiCallInProgress")
            assertNotNull(state)
        } finally {
            firebaseMessagingStatic.close()
            firebaseAppStatic.close()
        }
    }

    private fun getStaticField(fieldName: String): Any? =
        PushEngageTestSupport.getStaticField(fieldName)

    private fun setStaticField(fieldName: String, value: Any?) =
        PushEngageTestSupport.setStaticField(fieldName, value)
}
