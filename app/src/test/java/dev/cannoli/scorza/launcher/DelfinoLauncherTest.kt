package dev.cannoli.scorza.launcher

import dev.cannoli.igm.DELFINO_PROTOCOL_VERSION
import dev.cannoli.igm.DelfinoLaunchParams
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DelfinoLauncherTest {

    private fun params() = DelfinoLaunchParams(
        romPath = "/sd/Cannoli/Roms/GC/Mario Kart - Double Dash.iso",
        cannoliRoot = "/sd/Cannoli",
        savesDir = null,
        saveStatesDir = null,
        biosDir = "/sd/Cannoli/BIOS/GC",
        userDir = null,
        gameTitle = "Mario Kart Double Dash",
        platformTag = "GC",
        igmTriggerKeycodes = listOf(4, 109),
        colors = null,
        displaySettings = null,
        inputMapping = null,
    )

    @Test
    fun buildIntentTargetsDelfinoLaunchActivity() {
        val intent = DelfinoLauncher.buildIntent(params(), "dev.cannoli.delfino.debug")
        assertEquals("dev.cannoli.delfino.debug", intent.component?.packageName)
        assertEquals(DelfinoLauncher.ACTIVITY, intent.component?.className)
        assertEquals(
            DELFINO_PROTOCOL_VERSION,
            intent.getIntExtra(DelfinoLaunchParams.EXTRA_PROTOCOL, -1),
        )
    }

    @Test
    fun buildIntentParamsRoundTripThroughIntent() {
        val intent = DelfinoLauncher.buildIntent(params(), "dev.cannoli.delfino")
        val restored = DelfinoLaunchParams.readFromIntent(intent)
        assertEquals(
            "/sd/Cannoli/Roms/GC/Mario Kart - Double Dash.iso",
            restored?.romPath,
        )
        assertEquals("GC", restored?.platformTag)
        assertEquals(listOf(4, 109), restored?.igmTriggerKeycodes)
    }
}
