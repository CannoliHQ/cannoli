package dev.cannoli.scorza.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRunnerPreferenceTest {
    @Test fun `unresponsive RetroArch label still selects the external runner`() {
        assertTrue(isExplicitExternalRunner("RetroArch (Unknown)"))
    }

    @Test fun `unresponsive RicottaArch label still selects the external runner`() {
        assertTrue(isExplicitExternalRunner("RicottaArch (Unknown)"))
    }

    @Test fun `internal default and standalone labels do not select external runner`() {
        assertFalse(isExplicitExternalRunner(null))
        assertFalse(isExplicitExternalRunner("Internal"))
        assertFalse(isExplicitExternalRunner("Standalone"))
    }
}
