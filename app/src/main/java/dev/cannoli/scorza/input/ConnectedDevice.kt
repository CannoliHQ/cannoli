package dev.cannoli.scorza.input

data class ConnectedDevice(
    val androidDeviceId: Int,
    val descriptor: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val androidBuildModel: String,
    val sourceMask: Int,
    val connectedAtMillis: Long,
    val isBuiltIn: Boolean = false,
    val isExternal: Boolean = true,
)
