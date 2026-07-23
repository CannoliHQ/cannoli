package dev.cannoli.scorza.launcher

import dev.cannoli.igm.RICOTTA_PROTOCOL_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetroArchLauncherTest {

    @Test fun `gate fails when installed protocol is older`() {
        val verdict = RetroArchLauncher.checkProtocol(
            installedProtocol = RICOTTA_PROTOCOL_VERSION - 1,
            requiredProtocol = RICOTTA_PROTOCOL_VERSION,
        )
        assertTrue(verdict is ProtocolVerdict.UpdateRicotta)
    }

    @Test fun `gate fails asking to update cannoli when installed is newer`() {
        val verdict = RetroArchLauncher.checkProtocol(
            installedProtocol = RICOTTA_PROTOCOL_VERSION + 1,
            requiredProtocol = RICOTTA_PROTOCOL_VERSION,
        )
        assertTrue(verdict is ProtocolVerdict.UpdateCannoli)
    }

    @Test fun `gate passes on exact match`() {
        val verdict = RetroArchLauncher.checkProtocol(
            installedProtocol = RICOTTA_PROTOCOL_VERSION,
            requiredProtocol = RICOTTA_PROTOCOL_VERSION,
        )
        assertEquals(ProtocolVerdict.Ok, verdict)
    }
}
