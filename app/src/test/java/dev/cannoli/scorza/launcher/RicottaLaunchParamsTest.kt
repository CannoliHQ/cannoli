package dev.cannoli.scorza.launcher

import android.os.Parcel
import dev.cannoli.igm.BatteryDisplayMode
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.IgmDisplaySettings
import dev.cannoli.igm.ProtocolMismatchException
import dev.cannoli.igm.RICOTTA_PROTOCOL_VERSION
import dev.cannoli.igm.RicottaLaunchParams
import dev.cannoli.igm.TimeFormatMode
import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
            showWifi = true,
            showBluetooth = true,
            showVpn = true,
            showClock = true,
            batteryDisplay = BatteryDisplayMode.ICON,
            timeFormat = TimeFormatMode.TWENTY_FOUR_HOUR,
            buttonLabelSet = ButtonLabelSet.PLUMBER,
            confirmButton = ConfirmButton.SOUTH,
        ),
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
        assertEquals(original, roundTrip(original))
    }

    @Test fun `round trips null optional fields`() {
        val original = sample().copy(
            configFilePath = null,
            preferredRefreshRate = null,
            colors = null,
        )
        assertEquals(original, roundTrip(original))
    }

    @Test fun `rejects mismatched protocol version before reading other fields`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(RICOTTA_PROTOCOL_VERSION + 1) // wrong version as field #1
            parcel.writeString("garbage")                  // would mis-parse if read
            parcel.setDataPosition(0)
            val ex = assertThrows(ProtocolMismatchException::class.java) {
                RicottaLaunchParams.CREATOR.createFromParcel(parcel)
            }
            assertEquals(RICOTTA_PROTOCOL_VERSION + 1, ex.found)
            assertEquals(RICOTTA_PROTOCOL_VERSION, ex.expected)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `accepts matching protocol version`() {
        // Round-trip already exercises the matching path; assert it does not throw.
        roundTrip(sample())
    }

    @Test fun `writeToIntent then readFromIntent round trips`() {
        val intent = android.content.Intent()
        val params = sample()
        params.writeToIntent(intent)
        assertEquals(RICOTTA_PROTOCOL_VERSION, intent.getIntExtra(RicottaLaunchParams.EXTRA_PROTOCOL, -1))
        assertEquals(params, RicottaLaunchParams.readFromIntent(intent))
    }

    @Test fun `readFromIntent returns null on missing or mismatched protocol`() {
        val empty = android.content.Intent()
        org.junit.Assert.assertNull(RicottaLaunchParams.readFromIntent(empty))

        val bad = android.content.Intent()
        sample().writeToIntent(bad)
        bad.putExtra(RicottaLaunchParams.EXTRA_PROTOCOL, RICOTTA_PROTOCOL_VERSION + 1)
        org.junit.Assert.assertNull(RicottaLaunchParams.readFromIntent(bad))
    }
}
