package dev.cannoli.scorza.input.v2

data class MatchInput(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val androidBuildModel: String,
    val sourceMask: Int,
    val bluetoothMac: String? = null,
)

data class DeviceMatchRule(
    val name: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val androidBuildModel: String? = null,
    val sourceMask: Int? = null,
    val bluetoothMac: String? = null,
) {
    fun score(input: MatchInput): Int {
        var score = 0

        // MAC match wins over everything else for BT controllers. The MAC is the only stable
        // identifier across pairings; InputDevice.name and VID/PID can change at every re-pair
        // when the controller's HID firmware lies about its identity (CRKD, cheap clones).
        val ruleMac = bluetoothMac
        val macMatched = ruleMac != null && ruleMac.isNotEmpty() &&
            input.bluetoothMac != null && input.bluetoothMac.equals(ruleMac, ignoreCase = true)
        if (macMatched) {
            score += 200
        }

        val ruleVid = vendorId
        val rulePid = productId
        val vidPidMatched = ruleVid != null && rulePid != null && ruleVid != 0 && rulePid != 0 &&
            ruleVid == input.vendorId && rulePid == input.productId
        if (vidPidMatched) {
            score += 100
        }

        // Name only scores when vid+pid did not already match; vid+pid subsumes name identity.
        val ruleName = name
        if (!vidPidMatched && ruleName != null && ruleName.isNotEmpty() && ruleName == input.name) {
            score += 50
        }

        val ruleModel = androidBuildModel
        if (ruleModel != null && ruleModel.isNotEmpty() && ruleModel == input.androidBuildModel) {
            score += 100
        }

        val ruleMask = sourceMask
        if (ruleMask != null && ruleMask != 0 && (ruleMask and input.sourceMask) == ruleMask) {
            score += 10
        }

        return score
    }
}
