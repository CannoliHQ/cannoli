package dev.cannoli.scorza.launcher

import android.os.Parcel
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.RicottaLaunchParams
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
        igmTriggerKeycode = 110,
        quitOnFocusLoss = true,
        preferredRefreshRate = 60,
        colors = IgmColors(
            highlight = "#0AB9E6",
            text = "#FFFFFF",
            highlightText = "#000000",
            accent = "#E8C896",
            title = "#0AB9E6",
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
}
