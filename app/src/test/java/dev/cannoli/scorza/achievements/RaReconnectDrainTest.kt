package dev.cannoli.scorza.achievements

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaReconnectDrainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun callbacks() = shadowOf(cm).networkCallbacks.toList()

    private fun validated(yes: Boolean): NetworkCapabilities {
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        if (yes) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return caps
    }

    @Test fun `a network arriving sends the queue`() = runTest {
        var drains = 0
        val d = RaReconnectDrain(context, CoroutineScope(StandardTestDispatcher(testScheduler))) { drains++ }
        d.start()

        callbacks().single().onAvailable(mockNetwork())
        advanceUntilIdle()

        assertEquals(1, drains)
    }

    // A reconnection reaches both callbacks, and a drain sends the whole queue, so the second must
    // not start while the first is still going.
    @Test fun `one reconnection seen twice sends the queue once`() = runTest {
        var drains = 0
        val d = RaReconnectDrain(context, CoroutineScope(StandardTestDispatcher(testScheduler))) { drains++ }
        d.start()

        val cb = callbacks().single()
        cb.onAvailable(mockNetwork())
        cb.onCapabilitiesChanged(mockNetwork(), validated(true))
        advanceUntilIdle()

        assertEquals(1, drains)
    }

    @Test fun `a network that is not validated sends nothing`() = runTest {
        var drains = 0
        val d = RaReconnectDrain(context, CoroutineScope(StandardTestDispatcher(testScheduler))) { drains++ }
        d.start()

        callbacks().single().onCapabilitiesChanged(mockNetwork(), validated(false))
        advanceUntilIdle()

        assertEquals(0, drains)
    }

    @Test fun `stopping unregisters, so a background launcher is not listening`() = runTest {
        val d = RaReconnectDrain(context, CoroutineScope(StandardTestDispatcher(testScheduler))) {}
        d.start()
        assertEquals(1, callbacks().size)

        d.stop()

        assertEquals(0, callbacks().size)
    }

    private fun mockNetwork(): Network = org.robolectric.shadows.ShadowNetwork.newInstance(1)
}
