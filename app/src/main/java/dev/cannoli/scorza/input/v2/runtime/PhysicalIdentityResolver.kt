package dev.cannoli.scorza.input.v2.runtime

import android.content.Context
import android.bluetooth.BluetoothManager
import dev.cannoli.scorza.input.v2.ConnectedDevice

interface PhysicalIdentityResolver {
    fun identify(device: ConnectedDevice): PhysicalIdentity?
}

class BluetoothPhysicalIdentityResolver(
    private val context: Context,
) : PhysicalIdentityResolver {

    override fun identify(device: ConnectedDevice): PhysicalIdentity? {
        val mac = lookupBluetoothMac(device.name)
        if (mac != null) {
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${device.androidDeviceId} name='${device.name}' -> BT mac=$mac"
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

    private fun lookupBluetoothMac(deviceName: String): String? {
        if (deviceName.isEmpty()) return null
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter
            if (adapter == null) {
                dev.cannoli.scorza.util.InputLog.write("  BT lookup: no adapter")
                return null
            }
            if (!adapter.isEnabled) {
                dev.cannoli.scorza.util.InputLog.write("  BT lookup: adapter disabled")
                return null
            }
            val bonded = adapter.bondedDevices
            if (bonded == null) {
                dev.cannoli.scorza.util.InputLog.write("  BT lookup: bondedDevices=null")
                return null
            }
            dev.cannoli.scorza.util.InputLog.write(
                "  BT lookup for '$deviceName': bonded=${bonded.joinToString { "'${it.name}'(${it.address})" }}"
            )
            val matches = bonded.filter { it.name == deviceName }
            if (matches.size != 1) {
                dev.cannoli.scorza.util.InputLog.write(
                    "  BT lookup: ${matches.size} matches for '$deviceName' -> giving up"
                )
                return null
            }
            matches[0].address
        } catch (e: SecurityException) {
            dev.cannoli.scorza.util.InputLog.write(
                "  BT lookup: SecurityException (${e.message})"
            )
            null
        } catch (e: Exception) {
            dev.cannoli.scorza.util.InputLog.write(
                "  BT lookup: error (${e.message})"
            )
            null
        }
    }
}
