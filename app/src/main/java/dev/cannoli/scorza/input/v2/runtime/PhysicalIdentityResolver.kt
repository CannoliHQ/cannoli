package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.ConnectedDevice

interface PhysicalIdentityResolver {
    /**
     * Identify [device] using only signals available without consuming a BT queue entry: name
     * equality against the tracker's currently-queued bondedNames, then a wired fallback.
     *
     * The bridge orchestrates a second pass for un-identified external InputDevices via
     * [claimFifoMac] so iteration order doesn't let one device steal another's BT MAC.
     */
    fun identify(device: ConnectedDevice): PhysicalIdentity?

    /** Claim the next FIFO MAC from the BT tracker, or null when none remain. */
    fun claimFifoMac(): String? = null
}

class BluetoothPhysicalIdentityResolver(
    private val tracker: BtHidConnectionTracker,
    private val hints: dev.cannoli.scorza.input.v2.hints.ControllerHintTable? = null,
) : PhysicalIdentityResolver {

    override fun identify(device: ConnectedDevice): PhysicalIdentity? {
        // Curated wired-only list (e.g. handheld built-in keypads) bypass BT identification
        // entirely. This is more reliable than any heuristic because handheld OEMs don't ship
        // BT controllers under their own handheld brand.
        if (hints?.isWiredOnly(device.name) == true) {
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${device.androidDeviceId} name='${device.name}' -> Wired (hint: wired-only)"
            )
            return wiredFor(device)
        }

        val mac = tracker.claimByName(device.name)
        if (mac != null) {
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${device.androidDeviceId} name='${device.name}' -> BT mac=$mac (name match)"
            )
            return PhysicalIdentity.Bluetooth(mac)
        }

        if (device.vendorId != 0 && device.productId != 0 && device.descriptor.isNotEmpty()) {
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${device.androidDeviceId} name='${device.name}' -> Wired vid=${device.vendorId} pid=${device.productId}"
            )
            return PhysicalIdentity.Wired(device.vendorId, device.productId, device.descriptor)
        }
        dev.cannoli.scorza.util.InputLog.write(
            "  identify id=${device.androidDeviceId} name='${device.name}' -> null (no usable identity)"
        )
        return null
    }

    override fun claimFifoMac(): String? = tracker.claimNextFifo()

    fun isWiredOnly(name: String): Boolean = hints?.isWiredOnly(name) == true

    private fun wiredFor(device: ConnectedDevice): PhysicalIdentity? =
        if (device.vendorId != 0 && device.productId != 0 && device.descriptor.isNotEmpty()) {
            PhysicalIdentity.Wired(device.vendorId, device.productId, device.descriptor)
        } else null
}
