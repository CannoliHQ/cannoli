package dev.cannoli.igm

import android.os.Parcel
import android.os.Parcelable
import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton

// The host's IGM display settings, forwarded from the launcher so ricotta renders
// the IGM identically. (Excludes keyCodeName, which is a function each host supplies.)
data class IgmDisplaySettings(
    val fontSizeSp: Int,
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
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(fontSizeSp)
        dest.writeInt(portraitMarginPx)
        dest.writeInt(geometryWidthPct)
        dest.writeInt(geometryHeightPct)
        dest.writeInt(geometryXPct)
        dest.writeInt(geometryYPct)
        dest.writeInt(if (showWifi) 1 else 0)
        dest.writeInt(if (showBluetooth) 1 else 0)
        dest.writeInt(if (showVpn) 1 else 0)
        dest.writeInt(if (showClock) 1 else 0)
        dest.writeInt(batteryDisplay.ordinal)
        dest.writeInt(timeFormat.ordinal)
        dest.writeInt(buttonLabelSet.ordinal)
        dest.writeInt(confirmButton.ordinal)
    }

    companion object CREATOR : Parcelable.Creator<IgmDisplaySettings> {
        override fun createFromParcel(p: Parcel) = IgmDisplaySettings(
            fontSizeSp = p.readInt(),
            portraitMarginPx = p.readInt(),
            geometryWidthPct = p.readInt(),
            geometryHeightPct = p.readInt(),
            geometryXPct = p.readInt(),
            geometryYPct = p.readInt(),
            showWifi = p.readInt() != 0,
            showBluetooth = p.readInt() != 0,
            showVpn = p.readInt() != 0,
            showClock = p.readInt() != 0,
            batteryDisplay = BatteryDisplayMode.values()[p.readInt()],
            timeFormat = TimeFormatMode.values()[p.readInt()],
            buttonLabelSet = ButtonLabelSet.values()[p.readInt()],
            confirmButton = ConfirmButton.values()[p.readInt()],
        )

        override fun newArray(size: Int) = arrayOfNulls<IgmDisplaySettings>(size)
    }
}
