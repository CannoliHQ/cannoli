package dev.cannoli.scorza.romm

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RommDevicePairingTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsRepository
    private lateinit var store: RommConnectionStore
    private lateinit var client: RommClient

    private val initDto = DeviceAuthInitDto(
        deviceCode = "dc-1",
        userCode = "ABCD2345",
        verificationPath = "/pair/device",
        verificationPathComplete = "/pair/device?user_code=ABCD2345",
        expiresIn = 600,
        interval = 5,
    )
    private val tokenDto = DeviceAuthTokenDto(accessToken = "tok-1", deviceId = "dev-9")

    @Before fun setup() {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = tmp.newFolder("SD").absolutePath
        store = RommConnectionStore(ApplicationProvider.getApplicationContext())
        client = mockk()
        every { client.resolveBaseUrl("myhost") } returns "https://myhost"
        every { client.serverVersion() } returns "5.0.0"
        every { client.deviceAuthInit(any()) } returns initDto
    }

    private fun TestScope.pairing(): RommDevicePairing {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RommDevicePairing(
            client = client,
            store = store,
            settings = settings,
            scope = CoroutineScope(dispatcher),
            io = dispatcher,
            deviceIdentifier = { "and-1" },
            deviceName = { "Test Device" },
            clientVersion = { "1.8.0" },
        )
    }

    private fun pendingError() =
        RommException(400, """HTTP 400 Bad Request: {"detail":"authorization_pending"}""")

    @Test fun `pending then approved persists credentials and succeeds`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws pendingError() andThen tokenDto
        val p = pairing()
        p.start("myhost")
        runCurrent()
        assertEquals(
            PairingState.WaitingApproval("ABCD2345", "https://myhost/pair/device?user_code=ABCD2345"),
            p.state.value,
        )
        advanceUntilIdle()
        assertEquals(PairingState.Success, p.state.value)
        assertEquals("tok-1", store.token)
        assertEquals("dev-9", settings.rommDeviceId)
        assertEquals("Test Device", settings.rommDeviceName)
        assertEquals("1.8.0", settings.rommDeviceClientVersion)
        assertEquals("https://myhost", store.host)
    }

    @Test fun `init payload carries device identity and scopes`() = runTest {
        every { client.deviceAuthToken("dc-1") } returns tokenDto
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        verify {
            client.deviceAuthInit(
                match {
                    it.clientDeviceIdentifier == "and-1" &&
                        it.name == "Test Device" &&
                        it.client == "Cannoli" &&
                        it.platform == "android" &&
                        it.clientVersion == "1.8.0" &&
                        it.requestedScopes.containsAll(
                            listOf(
                                "me.read", "platforms.read", "roms.read", "collections.read",
                                "firmware.read", "assets.read", "assets.write",
                                "devices.read", "devices.write",
                            )
                        )
                }
            )
        }
    }

    @Test fun `slow_down extends the polling interval`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws
            RommException(400, """HTTP 400: {"detail":"slow_down"}""") andThen tokenDto
        val p = pairing()
        p.start("myhost")
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(p.state.value is PairingState.WaitingApproval)
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(p.state.value is PairingState.WaitingApproval)
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(PairingState.Success, p.state.value)
    }

    @Test fun `denied maps to DENIED and stops polling`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws
            RommException(400, """HTTP 400: {"detail":"access_denied"}""")
        val p = pairing()
        p.start("myhost")
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(PairingState.Failed(PairingFailure.DENIED), p.state.value)
        verify(exactly = 1) { client.deviceAuthToken("dc-1") }
    }

    @Test fun `expired_token maps to EXPIRED`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws
            RommException(400, """HTTP 400: {"detail":"expired_token"}""")
        val p = pairing()
        p.start("myhost")
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(PairingState.Failed(PairingFailure.EXPIRED), p.state.value)
    }

    @Test fun `deadline elapsing maps to EXPIRED`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws pendingError()
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Failed(PairingFailure.EXPIRED), p.state.value)
    }

    @Test fun `transient errors keep polling`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws
            RommException(null, "Network error: timeout") andThen tokenDto
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Success, p.state.value)
    }

    @Test fun `unreachable host maps to UNREACHABLE`() = runTest {
        every { client.resolveBaseUrl("myhost") } returns null
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Failed(PairingFailure.UNREACHABLE), p.state.value)
    }

    @Test fun `old server version fails before init`() = runTest {
        every { client.serverVersion() } returns "4.9.0"
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Failed(PairingFailure.SERVER_TOO_OLD), p.state.value)
        verify(exactly = 0) { client.deviceAuthInit(any()) }
    }

    @Test fun `unknown server version proceeds to init`() = runTest {
        every { client.serverVersion() } returns null
        every { client.deviceAuthToken("dc-1") } returns tokenDto
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Success, p.state.value)
    }

    @Test fun `init 404 maps to SERVER_TOO_OLD`() = runTest {
        every { client.deviceAuthInit(any()) } throws RommException(404, "HTTP 404 Not Found")
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Failed(PairingFailure.SERVER_TOO_OLD), p.state.value)
    }

    @Test fun `init 429 maps to RATE_LIMITED`() = runTest {
        every { client.deviceAuthInit(any()) } throws RommException(429, "HTTP 429")
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Failed(PairingFailure.RATE_LIMITED), p.state.value)
    }

    @Test fun `init network error maps to UNREACHABLE`() = runTest {
        every { client.deviceAuthInit(any()) } throws RommException(null, "Network error: reset")
        val p = pairing()
        p.start("myhost")
        advanceUntilIdle()
        assertEquals(PairingState.Failed(PairingFailure.UNREACHABLE), p.state.value)
    }

    @Test fun `cancel stops polling and returns to Idle`() = runTest {
        every { client.deviceAuthToken("dc-1") } throws pendingError()
        val p = pairing()
        p.start("myhost")
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        p.cancel()
        assertEquals(PairingState.Idle, p.state.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(PairingState.Idle, p.state.value)
        verify(exactly = 1) { client.deviceAuthToken("dc-1") }
    }

    @Test fun `cancel during in-flight token call suppresses success`() = runTest {
        lateinit var p: RommDevicePairing
        every { client.deviceAuthToken("dc-1") } answers {
            p.cancel()
            tokenDto
        }
        p = pairing()
        p.start("myhost")
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(PairingState.Idle, p.state.value)
        assertEquals(null, store.token)
        assertEquals(null, settings.rommDeviceId)
    }
}
