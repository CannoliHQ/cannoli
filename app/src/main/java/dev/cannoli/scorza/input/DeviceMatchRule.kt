package dev.cannoli.scorza.input

data class DeviceMatchRule(
    val name: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val androidBuildModel: String? = null,
    val sourceMask: Int? = null,
    val descriptor: String? = null,
)
