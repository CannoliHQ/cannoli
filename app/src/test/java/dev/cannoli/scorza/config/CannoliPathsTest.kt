package dev.cannoli.scorza.config

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CannoliPathsTest {
    private val paths = CannoliPaths("/sd")

    @Test
    fun retroArchCfg_isUnderConfigRetroArch() {
        assertEquals(File("/sd/Config/RetroArch/retroarch.cfg"), paths.retroArchCfg)
    }

    @Test
    fun customCfg_isUnderConfigRetroArch() {
        assertEquals(File("/sd/Config/RetroArch/custom.cfg"), paths.customCfg)
    }

    @Test
    fun globalOverrideCfg_isUnderConfigOverrides() {
        assertEquals(File("/sd/Config/Overrides/global.cfg"), paths.globalOverrideCfg)
    }

    @Test
    fun systemOverrideCfg_isUnderConfigOverridesSystems() {
        assertEquals(File("/sd/Config/Overrides/Systems/NES.cfg"), paths.systemOverrideCfg("NES"))
    }

    @Test
    fun gameOverrideCfg_isUnderConfigOverridesGames() {
        assertEquals(
            File("/sd/Config/Overrides/Games/NES/Super Mario Bros.cfg"),
            paths.gameOverrideCfg("NES", "Super Mario Bros"),
        )
    }
}
