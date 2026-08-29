package dev.cannoli.igm

import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton

enum class BatteryDisplayMode {
    HIDE, PERCENT, ICON;

    companion object {
        fun fromString(value: String?): BatteryDisplayMode =
            entries.firstOrNull { it.name == value } ?: PERCENT
    }
}

enum class TimeFormatMode {
    TWELVE_HOUR, TWENTY_FOUR_HOUR;

    companion object {
        fun fromString(value: String?): TimeFormatMode =
            entries.firstOrNull { it.name == value } ?: TWELVE_HOUR
    }
}

// Launcher-specific data the shared IGM needs, passed in by its host so cannoli-igm stays free of
// app/settings dependencies.
data class IGMHostConfig(
    val fontSizeSp: Int,
    val lineHeightSp: Float,
    val pillScale: Float,
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
