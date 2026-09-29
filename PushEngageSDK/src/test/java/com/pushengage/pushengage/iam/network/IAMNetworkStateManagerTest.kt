package com.pushengage.pushengage.iam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Observer semantics of the connectivity singleton: state transitions reach
 * every observer exactly once, duplicate registrations and duplicate system
 * callbacks don't double-notify, and removed observers stay silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IAMNetworkStateManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var manager: IAMNetworkStateManager
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setUp() {
        // The singleton may survive from an earlier test with a callback
        // registered on a stale ConnectivityManager — reset it so this class
        // tests a manager wired to THIS test's connectivity shadow.
        val instanceField = IAMNetworkStateManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager = IAMNetworkStateManager.getInstance(context)
    }

    private class RecordingObserver : IAMNetworkStateObserver {
        val notifications = mutableListOf<Boolean>()
        override fun networkStateDidChange(isConnected: Boolean) {
            notifications.add(isConnected)
        }
    }

    /** Drives the system connectivity callback the manager registered. */
    private fun systemReports(connected: Boolean) {
        val network = connectivityManager.activeNetwork!!
        shadowOf(connectivityManager).networkCallbacks.forEach {
            if (connected) it.onAvailable(network) else it.onLost(network)
        }
    }

    @Test
    fun `manager registers a system network callback`() {
        assertTrue(shadowOf(connectivityManager).networkCallbacks.isNotEmpty())
    }

    @Test
    fun `adding an observer immediately reports the current state`() {
        val observer = RecordingObserver()
        manager.addObserver(observer)
        assertEquals(listOf(manager.isConnected), observer.notifications)
    }

    @Test
    fun `observers are notified of connectivity transitions`() {
        systemReports(false) // force a known baseline
        val observer = RecordingObserver()
        manager.addObserver(observer)
        observer.notifications.clear()

        systemReports(true)
        assertEquals(listOf(true), observer.notifications)
        assertTrue(manager.isConnected)

        systemReports(false)
        assertEquals(listOf(true, false), observer.notifications)
        assertFalse(manager.isConnected)
    }

    @Test
    fun `duplicate system callbacks for the same state do not double-notify`() {
        systemReports(false)
        val observer = RecordingObserver()
        manager.addObserver(observer)
        observer.notifications.clear()

        systemReports(true)
        systemReports(true) // e.g. a second onAvailable for another network

        assertEquals(listOf(true), observer.notifications)
    }

    @Test
    fun `adding the same observer twice registers it once`() {
        systemReports(false)
        val observer = RecordingObserver()
        manager.addObserver(observer)
        manager.addObserver(observer)
        observer.notifications.clear()

        systemReports(true)

        assertEquals("one registration → one notification", listOf(true), observer.notifications)
    }

    @Test
    fun `removed observers are not notified`() {
        systemReports(false)
        val observer = RecordingObserver()
        manager.addObserver(observer)
        manager.removeObserver(observer)
        observer.notifications.clear()

        systemReports(true)

        assertTrue(observer.notifications.isEmpty())
    }

    @Test
    fun `removing a never-registered observer does not crash`() {
        manager.removeObserver(RecordingObserver())
    }

    /** Makes [isNetworkAvailable] see a validated internet connection. */
    private fun deviceIsOnline() {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(connectivityManager)
            .setNetworkCapabilities(connectivityManager.activeNetwork!!, caps)
    }

    private fun systemLoses(network: Network) {
        shadowOf(connectivityManager).networkCallbacks.forEach { it.onLost(network) }
    }

    /**
     * Losing ONE network is not the same as going offline. Observed on a device
     * (2026-08-10): after an airplane-mode cycle the torn-down mobile network's
     * `onLost` arrived ~36 s AFTER the replacement network was already up, and
     * the manager latched offline for the rest of the process — IAM analytics
     * stopped flushing until the app was restarted.
     */
    @Test
    fun `losing one network while the device is still online stays connected`() {
        deviceIsOnline()
        systemReports(true)
        val observer = RecordingObserver()
        manager.addObserver(observer)
        observer.notifications.clear()

        systemLoses(ShadowNetwork.newInstance(/* netId= */ 42))

        assertTrue("device is still online — must not latch offline", manager.isConnected)
        assertTrue("no spurious offline notification", observer.notifications.isEmpty())
    }

    @Test
    fun `losing the last network reports offline`() {
        deviceIsOnline()
        systemReports(true)
        val observer = RecordingObserver()
        manager.addObserver(observer)
        observer.notifications.clear()

        // Nothing is available any more: drop the capabilities first, so
        // re-checking availability agrees with the callback.
        shadowOf(connectivityManager)
            .setNetworkCapabilities(connectivityManager.activeNetwork!!, null)
        systemLoses(connectivityManager.activeNetwork!!)

        assertFalse(manager.isConnected)
        assertEquals(listOf(false), observer.notifications)
    }
}
