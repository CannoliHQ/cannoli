package dev.cannoli.scorza.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulatorSourceTest {
    @Test fun `fromRunnerLabel maps known labels`() {
        assertEquals(EmulatorSource.Standalone, EmulatorSource.fromRunnerLabel("Standalone"))
        assertEquals(EmulatorSource.Standalone, EmulatorSource.fromRunnerLabel("App"))
        assertNull(EmulatorSource.fromRunnerLabel(null))
        assertNull(EmulatorSource.fromRunnerLabel(""))
    }

    // Any label that is not Internal or an app named a separately installed RetroArch, a tier that
    // no longer exists. The core it chose is still runnable, so the label resolves to the runner
    // that is left rather than dropping the mapping.
    @Test fun `fromRunnerLabel treats any other label as Embedded`() {
        assertEquals(EmulatorSource.Embedded, EmulatorSource.fromRunnerLabel("RetroArch"))
        assertEquals(EmulatorSource.Embedded, EmulatorSource.fromRunnerLabel("RetroArch Plus"))
    }

    // v1 mapped "Internal" to the built-in libretro runner. That runner is gone, so the label has
    // to land on the embedded RetroArch rather than stranding the platform on a dead source.
    @Test fun `fromRunnerLabel migrates the retired Internal label onto Embedded`() {
        assertEquals(EmulatorSource.Embedded, EmulatorSource.fromRunnerLabel("Internal"))
    }
}
