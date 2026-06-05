package com.pushengage.pushengage.permissionhandling

import androidx.fragment.app.FragmentActivity
import com.pushengage.pushengage.Callbacks.PushEngagePermissionCallback
import com.pushengage.pushengage.PushEngage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PEPermissionFragmentTest {

    private lateinit var pushEngageStatic: MockedStatic<PushEngage>

    @Before
    fun setUp() {
        pushEngageStatic = Mockito.mockStatic(PushEngage::class.java)
    }

    @After
    fun tearDown() {
        pushEngageStatic.close()
    }

    @Test
    fun requestPermission_withNullActivity_invokesFailureCallback() {
        var grantedResult: Boolean? = null
        var errorResult: Error? = null

        PEPermissionFragment.requestPermission(null, object : PushEngagePermissionCallback {
            override fun onPermissionResult(granted: Boolean, error: Error?) {
                grantedResult = granted
                errorResult = error
            }
        })

        assertEquals(false, grantedResult)
        assertNotNull(errorResult)
        assertEquals("Activity cannot be null", errorResult?.message)
        pushEngageStatic.verifyNoInteractions()
    }

    @Test
    fun requestPermission_withNullCallback_doesNotCrash() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        PEPermissionFragment.requestPermission(activity, null)

        pushEngageStatic.verifyNoInteractions()
    }

    @Test
    @Config(sdk = [32])
    fun requestPermission_belowApi33_autoGrantsAndCallsSubscribe() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var grantedResult: Boolean? = null
        var errorResult: Error? = null

        PEPermissionFragment.requestPermission(activity, object : PushEngagePermissionCallback {
            override fun onPermissionResult(granted: Boolean, error: Error?) {
                grantedResult = granted
                errorResult = error
            }
        })

        assertEquals(true, grantedResult)
        assertNull(errorResult)
        pushEngageStatic.verify({ PushEngage.subscribe() }, times(1))

        // Regression: removeSelf() runs synchronously from onCreate, inside the
        // add-fragment commitNow() transaction. Using commitNow() there would
        // throw "FragmentManager is already executing transactions" (caught and
        // swallowed by removeSelf), leaking the fragment. Drain pending
        // transactions and assert it is actually gone.
        activity.supportFragmentManager.executePendingTransactions()
        assertNull(activity.supportFragmentManager.findFragmentByTag("PEPermissionFragment"))
    }

    @Test
    @Config(sdk = [33])
    fun requestPermission_api33_systemGrants_callbackFiresWithTrue() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var callbackInvoked = false
        var grantedResult: Boolean? = null
        var errorResult: Error? = null

        PEPermissionFragment.requestPermission(activity, object : PushEngagePermissionCallback {
            override fun onPermissionResult(granted: Boolean, error: Error?) {
                callbackInvoked = true
                grantedResult = granted
                errorResult = error
            }
        })

        // The fragment has been added and is waiting for onRequestPermissionsResult.
        // The callback must NOT have fired yet — proves the call is genuinely async.
        val fragment = activity.supportFragmentManager.findFragmentByTag("PEPermissionFragment")
                as PEPermissionFragment
        assertNotNull(fragment)
        assertEquals(false, callbackInvoked)

        // Simulate the system delivering "user tapped Allow".
        fragment.onRequestPermissionsResult(
            200,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED)
        )

        assertEquals(true, callbackInvoked)
        assertEquals(true, grantedResult)
        assertNull(errorResult)
        pushEngageStatic.verify({ PushEngage.subscribe() }, times(1))
    }

    @Test
    @Config(sdk = [33])
    fun requestPermission_api33_systemDenies_callbackFiresWithFalse() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var callbackInvoked = false
        var grantedResult: Boolean? = null
        var errorResult: Error? = null

        PEPermissionFragment.requestPermission(activity, object : PushEngagePermissionCallback {
            override fun onPermissionResult(granted: Boolean, error: Error?) {
                callbackInvoked = true
                grantedResult = granted
                errorResult = error
            }
        })

        val fragment = activity.supportFragmentManager.findFragmentByTag("PEPermissionFragment")
                as PEPermissionFragment
        assertNotNull(fragment)
        assertEquals(false, callbackInvoked)

        // Simulate the system delivering "user tapped Don't Allow".
        fragment.onRequestPermissionsResult(
            200,
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)
        )

        assertEquals(true, callbackInvoked)
        assertEquals(false, grantedResult)
        assertNotNull(errorResult)
        assertEquals("Permission denied", errorResult?.message)
        pushEngageStatic.verifyNoInteractions()
    }
}
