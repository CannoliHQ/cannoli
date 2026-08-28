package dev.cannoli.igm

import android.os.Parcel
import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IgmDisplaySettingsTest {

    private fun settings(
        battery: BatteryDisplayMode,
        time: TimeFormatMode,
        labels: ButtonLabelSet,
        confirm: ConfirmButton,
    ) = IgmDisplaySettings(
        fontSizeSp = 14,
        portraitMarginPx = 8,
        geometryWidthPct = 100,
        geometryHeightPct = 95,
        geometryXPct = 0,
        geometryYPct = 2,
        showWifi = true,
        showBluetooth = false,
        showVpn = true,
        showClock = false,
        batteryDisplay = battery,
        timeFormat = time,
        buttonLabelSet = labels,
        confirmButton = confirm,
    )

    private fun roundTrip(s: IgmDisplaySettings): IgmDisplaySettings {
        val parcel = Parcel.obtain()
        s.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val restored = IgmDisplaySettings.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        return restored
    }

    @Test
    fun `every enum value survives the round trip`() {
        for (battery in BatteryDisplayMode.entries) {
            for (time in TimeFormatMode.entries) {
                for (labels in ButtonLabelSet.entries) {
                    for (confirm in ConfirmButton.entries) {
                        val s = settings(battery, time, labels, confirm)
                        assertEquals(s, roundTrip(s))
                    }
                }
            }
        }
    }

    /**
     * The sender is a separate build, so it can name a value this one has never heard of.
     *
     * Falling back to the default is what keeps a newer launcher from taking the IGM down inside
     * createFromParcel, and naming the values at all is what stops an inserted enum constant from
     * silently retargeting a shipped setting.
     */
    @Test
    fun `an unknown enum name decodes to the default`() {
        val parcel = Parcel.obtain()
        // The six geometry values then the four status bar flags, all ahead of the enums.
        repeat(10) { parcel.writeInt(0) }
        parcel.writeString("SOLAR")
        parcel.writeString("DECIMAL")
        parcel.writeString("HIEROGLYPHS")
        parcel.writeString("NORTH")
        parcel.setDataPosition(0)
        val restored = IgmDisplaySettings.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertEquals(BatteryDisplayMode.PERCENT, restored.batteryDisplay)
        assertEquals(TimeFormatMode.TWELVE_HOUR, restored.timeFormat)
        assertEquals(ButtonLabelSet.PLUMBER, restored.buttonLabelSet)
        assertEquals(ConfirmButton.EAST, restored.confirmButton)
    }

    @Test
    fun `a missing enum name decodes to the default`() {
        val parcel = Parcel.obtain()
        repeat(10) { parcel.writeInt(0) }
        repeat(4) { parcel.writeString(null) }
        parcel.setDataPosition(0)
        val restored = IgmDisplaySettings.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertEquals(BatteryDisplayMode.PERCENT, restored.batteryDisplay)
        assertEquals(TimeFormatMode.TWELVE_HOUR, restored.timeFormat)
        assertEquals(ButtonLabelSet.PLUMBER, restored.buttonLabelSet)
        assertEquals(ConfirmButton.EAST, restored.confirmButton)
    }
}
