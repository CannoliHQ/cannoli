package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DeviceRegistrarTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var settings: SettingsRepository
    private lateinit var client: RommClient
    private lateinit var registrar: DeviceRegistrar

    @Before fun setup() {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = tmp.newFolder("SD").absolutePath
        client = mockk()
        registrar = DeviceRegistrar(settings, client)
    }

    @Test fun register_stores_device_id_and_name() {
        every { client.registerDevice(any()) } returns DeviceRegisterResponse(deviceId = "uuid-9")
        val id = registrar.register("My Odin")
        assertEquals("uuid-9", id)
        assertEquals("uuid-9", settings.rommDeviceId)
        assertEquals("My Odin", settings.rommDeviceName)
        assertTrue(registrar.isRegistered())
    }

    @Test fun register_stores_client_version() {
        every { client.registerDevice(any()) } returns DeviceRegisterResponse(deviceId = "uuid-9")
        registrar.register("My Odin")
        assertTrue(!settings.rommDeviceClientVersion.isNullOrEmpty())
    }

    @Test fun register_returns_device_id() {
        every { client.registerDevice(any()) } returns DeviceRegisterResponse(deviceId = "uuid-xyz")
        val id = registrar.register("Test Device")
        assertEquals("uuid-xyz", id)
    }

    @Test fun device_id_reflects_registered_state() {
        every { client.registerDevice(any()) } returns DeviceRegisterResponse(deviceId = "uuid-9")
        assertFalse(registrar.isRegistered())
        assertEquals(null, registrar.deviceId())
        registrar.register("My Odin")
        assertTrue(registrar.isRegistered())
        assertEquals("uuid-9", registrar.deviceId())
    }

    @Test fun clear_wipes_identity() {
        every { client.registerDevice(any()) } returns DeviceRegisterResponse(deviceId = "uuid-9")
        registrar.register("My Odin")
        registrar.clear()
        assertFalse(registrar.isRegistered())
        assertEquals(null, settings.rommDeviceId)
        assertEquals(null, settings.rommDeviceName)
        assertEquals(null, settings.rommDeviceClientVersion)
    }

    @Test fun default_device_name_is_non_empty() {
        assertTrue(registrar.defaultDeviceName().isNotEmpty())
    }
}
