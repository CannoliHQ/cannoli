package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.ConnectedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectedDeviceFactoryTest {

    @Test
    fun maps_all_fields_into_connected_device() {
        val out = ConnectedDeviceFactory.fromFields(
            androidDeviceId = 12,
            descriptor = "abc123",
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
            androidBuildModel = "RP4PRO",
            sourceMask = 0x1041,
            connectedAtMillis = 1_000L,
        )
        val expected = ConnectedDevice(
            androidDeviceId = 12,
            descriptor = "abc123",
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
            androidBuildModel = "RP4PRO",
            sourceMask = 0x1041,
            connectedAtMillis = 1_000L,
            isBuiltIn = false,
        )
        assertEquals(expected, out)
    }

    @Test
    fun null_descriptor_becomes_empty_string() {
        val out = ConnectedDeviceFactory.fromFields(
            androidDeviceId = 1,
            descriptor = null,
            name = "X",
            vendorId = 1,
            productId = 2,
            androidBuildModel = "M",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        assertEquals("", out.descriptor)
    }

    @Test
    fun null_name_becomes_empty_string() {
        val out = ConnectedDeviceFactory.fromFields(
            androidDeviceId = 1,
            descriptor = "d",
            name = null,
            vendorId = 1,
            productId = 2,
            androidBuildModel = "M",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        assertEquals("", out.name)
    }

    @Test
    fun is_built_in_defaults_to_false() {
        val out = ConnectedDeviceFactory.fromFields(
            androidDeviceId = 1,
            descriptor = "d",
            name = "X",
            vendorId = 1,
            productId = 2,
            androidBuildModel = "M",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        assertFalse(out.isBuiltIn)
    }
}
