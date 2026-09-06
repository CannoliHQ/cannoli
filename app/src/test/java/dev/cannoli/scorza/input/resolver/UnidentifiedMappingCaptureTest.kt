package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgParser
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnidentifiedMappingCaptureTest {

    private fun device(
        name: String = "Some Pad",
        buildModel: String = "AYN Thor",
        sourceMask: Int = 16777233,
    ) = ConnectedDevice(
        androidDeviceId = 1,
        descriptor = "desc",
        name = name,
        vendorId = 8224,
        productId = 273,
        androidBuildModel = buildModel,
        sourceMask = sourceMask,
        connectedAtMillis = 0L,
    )

    @Test
    fun `records what the device says about itself`() {
        val lines = unidentifiedMapping(device(), "Controller").unmodeledLines
        assertEquals(
            listOf(
                "submission_build_model = \"AYN Thor\"",
                "submission_source_mask = \"16777233\"",
            ),
            lines,
        )
    }

    @Test
    fun `capture keys are not match keys`() {
        val mapping = unidentifiedMapping(device(), "Controller")
        assertNull(mapping.match.androidBuildModel)
        assertNull(mapping.match.sourceMask)
    }

    @Test
    fun `a device with no model reports only the source mask`() {
        val lines = unidentifiedMapping(device(buildModel = ""), "Controller").unmodeledLines
        assertEquals(listOf("submission_source_mask = \"16777233\""), lines)
    }

    // A quote would produce a line the parser cannot match, so the capture would silently vanish on
    // the next read while the cfg looked fine.
    @Test
    fun `a quote in the model name cannot break the line`() {
        val lines = unidentifiedMapping(device(buildModel = "Odd\"Model"), "Controller").unmodeledLines
        assertEquals("submission_build_model = \"OddModel\"", lines.first())
    }

    // The point of the capture is that it survives to whoever curates the pad, and the wizard's
    // output is edited before that happens: the button editor rewrites the cfg.
    @Test
    fun `capture survives a write and read round trip`() {
        val mapping = unidentifiedMapping(device(), "Controller")
        val entry = RetroArchCfgParser.parse(RetroArchCfgWriter.write(mapping), fileName = "x.cfg")
        assertTrue("submission_build_model = \"AYN Thor\"" in entry!!.unmodeledLines)
        assertTrue("submission_source_mask = \"16777233\"" in entry.unmodeledLines)
    }
}
