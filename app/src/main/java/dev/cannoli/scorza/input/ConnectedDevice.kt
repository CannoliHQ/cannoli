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
    /**
     * Which of Android's four trigger axes this pad says it reports, out of AXIS_LTRIGGER,
     * AXIS_RTRIGGER, AXIS_BRAKE and AXIS_GAS.
     *
     * Only ever used to choose which axis a trigger binding names, never to decide what controls
     * the hardware has: drivers declare axes that lead nowhere, and the press-to-bind capture is
     * the only truth about that.
     */
    val declaredTriggerAxes: Set<Int> = emptySet(),
)
