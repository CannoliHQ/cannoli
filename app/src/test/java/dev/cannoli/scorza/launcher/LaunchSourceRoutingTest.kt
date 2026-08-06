package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.EmulatorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchSourceRoutingTest {

    private fun pick(
        gameSource: EmulatorSource? = null,
        platformSource: EmulatorSource? = null,
        ra: Boolean = false,
        standalone: Boolean = false,
    ) = LaunchManager.pickSource(gameSource, platformSource, { ra }, { standalone })

    @Test fun `a game choice wins over the platform choice`() = assertEquals(
        EmulatorSource.Standalone,
        pick(EmulatorSource.Standalone, EmulatorSource.RetroArch, ra = true, standalone = true),
    )

    @Test fun `the platform choice applies when the game has none`() = assertEquals(
        EmulatorSource.RetroArch,
        pick(platformSource = EmulatorSource.RetroArch, ra = true, standalone = true),
    )

    @Test fun `with no choice and only a standalone app it picks Standalone`() =
        assertEquals(EmulatorSource.Standalone, pick(standalone = true))

    @Test fun `with no choice and a retroarch core it defers to retroarch`() =
        assertNull(pick(ra = true, standalone = true))

    @Test fun `with no choice and nothing available at all it defers`() = assertNull(pick())

    @Test fun `an explicit RetroArch choice never falls back to standalone`() = assertEquals(
        EmulatorSource.RetroArch,
        pick(platformSource = EmulatorSource.RetroArch, standalone = true),
    )

    @Test fun `an explicit Standalone choice never falls back to a core`() = assertEquals(
        EmulatorSource.Standalone,
        pick(platformSource = EmulatorSource.Standalone, ra = true),
    )

    // A stored choice decides on its own, so the filesystem and package probes must not run.
    @Test fun `a stored choice does not evaluate the availability probes`() {
        var probed = false
        LaunchManager.pickSource(
            gameSource = null,
            platformSource = EmulatorSource.RetroArch,
            raAvailable = { probed = true; true },
            standaloneAvailable = { probed = true; true },
        )
        assertFalse("a decided source must not trigger an availability probe", probed)
    }
}
