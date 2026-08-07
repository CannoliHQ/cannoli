package dev.cannoli.scorza.launcher

import android.os.Parcel
import dev.cannoli.igm.BatteryDisplayMode
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.IgmDisplaySettings
import dev.cannoli.igm.RicottaLaunchParams
import dev.cannoli.igm.TimeFormatMode
import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RicottaLaunchParamsTest {

    private fun sample() = RicottaLaunchParams(
        coreId = "gambatte",
        romPath = "/sd/roms/gb/zelda.gb",
        configFilePath = "/sd/cannoli/retroarch.cfg",
        gameTitle = "Zelda",
        stateBasePath = "/sd/cannoli/states/zelda",
        cannoliRoot = "/sd/cannoli",
        platformTag = "GB",
        platformName = "Game Boy",
        igmTriggerKeycodes = listOf(4, 110),
        quitOnFocusLoss = true,
        preferredRefreshRate = 60,
        colors = IgmColors(
            highlight = "#0AB9E6",
            text = "#FFFFFF",
            highlightText = "#000000",
            accent = "#E8C896",
            title = "#0AB9E6",
        ),
        displaySettings = IgmDisplaySettings(
            fontSizeSp = 24,
            portraitMarginPx = 0,
            geometryWidthPct = 100,
            geometryHeightPct = 100,
            geometryXPct = 0,
            geometryYPct = 0,
            showWifi = true,
            showBluetooth = true,
            showVpn = true,
            showClock = true,
            batteryDisplay = BatteryDisplayMode.ICON,
            timeFormat = TimeFormatMode.TWENTY_FOUR_HOUR,
            buttonLabelSet = ButtonLabelSet.PLUMBER,
            confirmButton = ConfirmButton.SOUTH,
        ),
        inputMapping = dev.cannoli.igm.IgmInputMapping(
            buttonKeycodes = mapOf(
                dev.cannoli.igm.CanonicalButton.BTN_WEST to listOf(100),
                dev.cannoli.igm.CanonicalButton.BTN_EAST to listOf(96),
            ),
            menuConfirm = dev.cannoli.igm.CanonicalButton.BTN_EAST,
            menuBack = dev.cannoli.igm.CanonicalButton.BTN_SOUTH,
        ),
        localeTag = "pt-BR",
        romBaseName = "Zelda (USA)",
    )

    private fun roundTrip(params: RicottaLaunchParams): RicottaLaunchParams {
        val parcel = Parcel.obtain()
        try {
            params.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return RicottaLaunchParams.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `round trips all fields`() {
        val original = sample()
        val restored = roundTrip(original)
        assertEquals(original, restored)
        assertEquals(listOf(100), restored.inputMapping?.buttonKeycodes?.get(dev.cannoli.igm.CanonicalButton.BTN_WEST))
        assertEquals(dev.cannoli.igm.CanonicalButton.BTN_EAST, restored.inputMapping?.menuConfirm)
        assertEquals(dev.cannoli.igm.CanonicalButton.BTN_SOUTH, restored.inputMapping?.menuBack)
    }

    @Test fun `round trips null optional fields`() {
        val original = sample().copy(
            configFilePath = null,
            preferredRefreshRate = null,
            colors = null,
        )
        assertEquals(original, roundTrip(original))
    }

    // localeTag is the last field precisely so this degrades instead of corrupting the ones before
    // it. Sender and receiver ship together, so this only covers a stale install.
    @Test fun `an older parcel that omits the trailing locale tag reads as no override`() {
        val params = sample()
        val parcel = Parcel.obtain()
        try {
            parcel.writeString(params.coreId)
            parcel.writeString(params.romPath)
            parcel.writeString(params.configFilePath)
            parcel.writeString(params.gameTitle)
            parcel.writeString(params.stateBasePath)
            parcel.writeString(params.cannoliRoot)
            parcel.writeString(params.platformTag)
            parcel.writeString(params.platformName)
            parcel.writeIntArray(params.igmTriggerKeycodes.toIntArray())
            parcel.writeInt(1)
            parcel.writeInt(1)
            parcel.writeInt(params.preferredRefreshRate!!)
            parcel.writeParcelable(params.colors, 0)
            parcel.writeParcelable(params.displaySettings, 0)
            parcel.writeParcelable(params.inputMapping, 0)
            parcel.setDataPosition(0)

            assertEquals(params.copy(localeTag = "", romBaseName = ""), RicottaLaunchParams.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }

    // romBaseName is now the last field, for the same reason localeTag was: a stale sender's
    // parcel must degrade to empty here rather than shift every field before it.
    @Test fun `an older parcel that omits the trailing rom base name reads as empty`() {
        val params = sample()
        val parcel = Parcel.obtain()
        try {
            parcel.writeString(params.coreId)
            parcel.writeString(params.romPath)
            parcel.writeString(params.configFilePath)
            parcel.writeString(params.gameTitle)
            parcel.writeString(params.stateBasePath)
            parcel.writeString(params.cannoliRoot)
            parcel.writeString(params.platformTag)
            parcel.writeString(params.platformName)
            parcel.writeIntArray(params.igmTriggerKeycodes.toIntArray())
            parcel.writeInt(1)
            parcel.writeInt(1)
            parcel.writeInt(params.preferredRefreshRate!!)
            parcel.writeParcelable(params.colors, 0)
            parcel.writeParcelable(params.displaySettings, 0)
            parcel.writeParcelable(params.inputMapping, 0)
            parcel.writeString(params.localeTag)
            parcel.setDataPosition(0)

            assertEquals(params.copy(romBaseName = ""), RicottaLaunchParams.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `writeToIntent then readFromIntent round trips`() {
        val intent = android.content.Intent()
        val params = sample()
        params.writeToIntent(intent)
        assertEquals(params, RicottaLaunchParams.readFromIntent(intent))
    }
}
