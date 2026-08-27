package dev.cannoli.scorza.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class CannoliPathsTest {
    private val paths = CannoliPaths("/sd")

    @Test
    fun retroArchCfg_isGeneratedSoItLivesUnderInternal() {
        assertEquals(File("/sd/Config/Internal/RetroArch/retroarch.cfg"), paths.retroArchCfg)
    }

    @Test
    // Every override file we write tells the user to edit this one, so it sits at the top.
    fun customCfg_isAtTheTopOfConfig() {
        assertEquals(File("/sd/Config/custom.cfg"), paths.customCfg)
    }

    @Test
    fun globalOverrideCfg_isUnderConfigOverrides() {
        assertEquals(File("/sd/Config/Overrides/global.cfg"), paths.globalOverrideCfg)
    }

    @Test
    fun systemOverrideCfg_isKeyedByPlatformThenCore() {
        assertEquals(
            File("/sd/Config/Overrides/Systems/NES/nestopia.cfg"),
            paths.systemOverrideCfg("NES", "nestopia"),
        )
    }

    @Test
    fun sharedCfgs_sitBesideTheCoreKeyedOnesAndDoNotNameACore() {
        val paths = CannoliPaths("/root")
        assertEquals(
            paths.systemOverrideCfg("NES", "nestopia").parentFile,
            paths.systemSharedCfg("NES").parentFile,
        )
        assertEquals(
            paths.gameOverrideCfg("NES", "Super Mario Bros", "nestopia").parentFile,
            paths.gameSharedCfg("NES", "Super Mario Bros").parentFile,
        )
        assertEquals("cannoli.cfg", paths.systemSharedCfg("NES").name)
        assertEquals("cannoli.cfg", paths.gameSharedCfg("NES", "Super Mario Bros").name)
    }

    @Test
    fun gameOverrideCfg_isKeyedByCoreInsideTheGameDirectory() {
        assertEquals(
            File("/sd/Config/Overrides/Games/NES/Super Mario Bros/nestopia.cfg"),
            paths.gameOverrideCfg("NES", "Super Mario Bros", "nestopia"),
        )
    }

    // The directory is what AtomicRename moves, so it has to be the cfg's parent exactly.
    @Test
    fun gameOverrideDir_isTheParentOfEveryCoreCfgForThatGame() {
        val dir = paths.gameOverrideDir("NES", "Super Mario Bros")
        assertEquals(File("/sd/Config/Overrides/Games/NES/Super Mario Bros"), dir)
        assertEquals(dir, paths.gameOverrideCfg("NES", "Super Mario Bros", "fceumm").parentFile)
    }

    // One game on two cores keeps two files, which is the whole point of the tier.
    @Test
    fun gameOverrideCfg_differsPerCore() {
        assertNotEquals(
            paths.gameOverrideCfg("NES", "Super Mario Bros", "nestopia"),
            paths.gameOverrideCfg("NES", "Super Mario Bros", "fceumm"),
        )
    }
}
