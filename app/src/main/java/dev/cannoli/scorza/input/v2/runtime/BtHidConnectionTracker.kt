package dev.cannoli.scorza.input.v2.runtime

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Tracks BT HID profile connection events so we can map an [android.view.InputDevice] back to its
 * Bluetooth MAC even when [InputDevice.name] doesn't match a bonded device's name (the CRKD /
 * Switch-Pro / cheap-clone case where the controller's HID descriptor lies about its identity).
 *
 * Maintains a queue of recent (mac, bondedName, timestamp) entries populated by:
 *   - a one-shot HID_HOST profile snapshot at start() for already-connected controllers (cold start);
 *   - the system's HID profile state-change broadcast for live pair/unpair (hot pair).
 *
 * Consumers call [claimMacFor] which atomically removes and returns the best-matching entry:
 *   1) entry whose bondedName == InputDevice.name (strongest signal);
 *   2) FIFO head if any entries remain (works because the BT->InputDevice pipeline preserves order);
 *   3) null if the queue is empty.
 *
 * The pathological case is two name-failers paired within milliseconds at cold start, where FIFO
 * order is arbitrary because the snapshot has no temporal info. In that case the user re-customizes
 * once and MAC keying carries it forward.
 */
class BtHidConnectionTracker(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val mac: String, val bondedName: String, val capturedAtMs: Long)

    private val queue: ArrayDeque<Entry> = ArrayDeque()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_HID_CONNECTION_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
            val mac = device.address ?: return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val name = safeName(device) ?: return
                    enqueue(mac, name)
                    dev.cannoli.scorza.util.InputLog.write("  bt-hid connected mac=$mac name='$name'")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.removeAll { it.mac == mac }
                    dev.cannoli.scorza.util.InputLog.write("  bt-hid disconnected mac=$mac")
                }
            }
        }
    }

    fun start() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_HID_CONNECTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        snapshotConnectedHidHosts()
    }

    fun stop() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
        queue.clear()
    }

    /**
     * Pop the queue entry whose bondedName equals [inputDeviceName] and return its MAC, or null
     * when there's no name match. Used in pass 1 of the bridge settle so honest controllers
     * claim their own MAC regardless of enumeration order.
     */
    fun claimByName(inputDeviceName: String): String? {
        drainStale()
        val match = queue.firstOrNull { it.bondedName == inputDeviceName } ?: return null
        queue.remove(match)
        return match.mac
    }

    /**
     * Pop the FIFO head and return its MAC, or null when the queue is empty. Used in pass 2 of
     * the bridge settle for unidentified-by-name external InputDevices.
     */
    fun claimNextFifo(): String? {
        drainStale()
        return queue.removeFirstOrNull()?.mac
    }

    /** Look up the bonded name for a MAC currently in the queue. */
    fun bondedNameFor(mac: String): String? = queue.firstOrNull { it.mac == mac }?.bondedName

    private fun enqueue(mac: String, bondedName: String) {
        queue.removeAll { it.mac == mac }
        queue.addLast(Entry(mac, bondedName, clock()))
    }

    private fun drainStale() {
        val cutoff = clock() - STALE_AFTER_MS
        while (queue.isNotEmpty() && queue.first().capturedAtMs < cutoff) {
            queue.removeFirst()
        }
    }

    private fun snapshotConnectedHidHosts() {
        try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter
            if (adapter == null || !adapter.isEnabled) {
                dev.cannoli.scorza.util.InputLog.write("  bt-hid snapshot: adapter unavailable")
                return
            }
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != PROFILE_HID_HOST) return
                    try {
                        val now = clock()
                        for (d in proxy.connectedDevices) {
                            val name = safeName(d) ?: continue
                            queue.removeAll { it.mac == d.address }
                            queue.addLast(Entry(d.address, name, now))
                            dev.cannoli.scorza.util.InputLog.write(
                                "  bt-hid snapshot: mac=${d.address} name='$name'"
                            )
                        }
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, PROFILE_HID_HOST)
        } catch (e: SecurityException) {
            dev.cannoli.scorza.util.InputLog.write("  bt-hid snapshot: SecurityException (${e.message})")
        } catch (e: Exception) {
            dev.cannoli.scorza.util.InputLog.write("  bt-hid snapshot: error (${e.message})")
        }
    }

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    companion object {
        // BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED is hidden from the public SDK on most
        // levels; the action string is stable across versions.
        private const val ACTION_HID_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"

        // BluetoothProfile.HID_HOST is annotated @hide; the integer is stable.
        private const val PROFILE_HID_HOST = 4

        // Keep entries around long enough to survive bridge settle delay (500ms) plus headroom.
        private const val STALE_AFTER_MS = 10_000L
    }
}
