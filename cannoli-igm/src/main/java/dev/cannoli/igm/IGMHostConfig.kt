package dev.cannoli.igm

import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton

enum class BatteryDisplayMode { HIDE, PERCENT, ICON }

enum class TimeFormatMode { TWELVE_HOUR, TWENTY_FOUR_HOUR }

// port >= 0 is an assigned controller port; a negative port means unassigned.
data class PlayerSlotInfo(val port: Int, val displayName: String)

// Launcher-specific data the shared IGM needs, passed in by each host (the launcher's
// built-in runner and ricotta) so cannoli-igm stays free of app/settings dependencies.
data class IGMHostConfig(
    val fontSizeSp: Int,
    // Expected: fontSizeSp + 10 (matches the launcher's igm line-height derivation).
    val lineHeightSp: Int,
    // Expected: fontSizeSp / 22f (matches the launcher's igm scale-factor derivation).
    val scaleFactor: Float,
    val portraitMarginPx: Int,
    val geometryWidthPct: Int,
    val geometryHeightPct: Int,
    val geometryXPct: Int,
    val geometryYPct: Int,
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
