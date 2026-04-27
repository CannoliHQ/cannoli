package dev.cannoli.scorza.input.v2

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
) {
    fun toMatchInput(): MatchInput = MatchInput(
        name = name,
        vendorId = vendorId,
        productId = productId,
        androidBuildModel = androidBuildModel,
        sourceMask = sourceMask,
    )
}
