package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.ConnectedDevice

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
        declaredTriggerAxes: Set<Int> = emptySet(),
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
        declaredTriggerAxes = declaredTriggerAxes,
    )
}
