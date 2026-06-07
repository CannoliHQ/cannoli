package dev.cannoli.igm

import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton

enum class BatteryDisplayMode { HIDE, PERCENT, ICON }

enum class TimeFormatMode { TWELVE_HOUR, TWENTY_FOUR_HOUR }

data class PlayerSlotInfo(val port: Int, val displayName: String)

// Launcher-specific data the shared IGM needs, passed in by each host (the launcher's
// built-in runner and ricotta) so cannoli-igm stays free of app/settings dependencies.
data class IGMHostConfig(
    val fontSizeSp: Int,
    val lineHeightSp: Int,
    val scaleFactor: Float,
    val portraitMarginPx: Int,
    val showWifi: Boolean,
    val showBluetooth: Boolean,
    val showVpn: Boolean,
    val showClock: Boolean,
    val batteryDisplay: BatteryDisplayMode,
    val timeFormat: TimeFormatMode,
    val buttonLabelSet: ButtonLabelSet,
    val confirmButton: ConfirmButton,
    val keyCodeName: (Int) -> String,
)
