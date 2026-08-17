package dev.cannoli.scorza.input

import dev.cannoli.ui.components.RommStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommStatusTest {
    @Test fun notConfiguredIsNull() {
        assertNull(rommStatusFrom(isConfigured = false, host = "romm.home.net", serverVersion = "3.0"))
    }
    @Test fun configuredWithVersionIsReachable() {
        assertEquals(RommStatus("romm.home.net", true), rommStatusFrom(true, "romm.home.net", "3.0"))
    }
    @Test fun configuredWithoutVersionIsUnreachable() {
        assertEquals(RommStatus("romm.home.net", false), rommStatusFrom(true, "romm.home.net", null))
    }
}
