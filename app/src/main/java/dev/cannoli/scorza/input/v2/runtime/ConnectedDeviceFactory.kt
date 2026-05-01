package dev.cannoli.scorza.input.v2.runtime

import android.os.Build
import android.view.InputDevice
import dev.cannoli.scorza.input.v2.ConnectedDevice

object ConnectedDeviceFactory {

    fun fromFields(
        androidDeviceId: Int,
        descriptor: String?,
        name: String?,
        vendorId: Int,
        productId: Int,
        androidBuildModel: String,
        sourceMask: Int,
        connectedAtMillis: Long,
        isBuiltIn: Boolean = false,
        isExternal: Boolean = true,
    ): ConnectedDevice = ConnectedDevice(
        androidDeviceId = androidDeviceId,
        descriptor = descriptor ?: "",
        name = name ?: "",
        vendorId = vendorId,
        productId = productId,
        androidBuildModel = androidBuildModel,
        sourceMask = sourceMask,
        connectedAtMillis = connectedAtMillis,
        isBuiltIn = isBuiltIn,
        isExternal = isExternal,
    )

    fun fromInputDevice(
        device: InputDevice,
        connectedAtMillis: Long,
        isBuiltIn: Boolean = false,
    ): ConnectedDevice = fromFields(
        androidDeviceId = device.id,
        descriptor = device.descriptor,
        name = device.name,
        vendorId = device.vendorId,
        productId = device.productId,
        androidBuildModel = Build.MODEL ?: "",
        sourceMask = device.sources,
        connectedAtMillis = connectedAtMillis,
        isBuiltIn = isBuiltIn,
        isExternal = device.isExternal,
    )
}
