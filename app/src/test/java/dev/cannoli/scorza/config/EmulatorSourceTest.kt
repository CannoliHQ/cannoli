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

    @Test fun `fromRunnerLabel treats any other label as Embedded`() {
        assertEquals(EmulatorSource.Embedded, EmulatorSource.fromRunnerLabel("RetroArch"))
        assertEquals(EmulatorSource.Embedded, EmulatorSource.fromRunnerLabel("RetroArch Plus"))
    }

    @Test fun `fromRunnerLabel migrates the retired Internal label onto Embedded`() {
        assertEquals(EmulatorSource.Embedded, EmulatorSource.fromRunnerLabel("Internal"))
    }
}
