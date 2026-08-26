package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.resolver.RetroArchAutoconfigImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroArchCfgWriterTest {

    private fun sampleMapping() = DeviceMapping(
        id = "8BitDo_Pro_2",
        displayName = "Living Room Pad",
        match = DeviceMatchRule(
            name = "8BitDo Pro 2",
            vendorId = 11720,
            productId = 24582,
        ),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
            CanonicalButton.BTN_UP to listOf(InputBinding.Hat(16, HatDirection.UP)),
            CanonicalButton.BTN_L2 to listOf(
                InputBinding.Axis(
                    axis = 23, restingValue = 0f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                )
            ),
            CanonicalButton.BTN_L3 to listOf(InputBinding.Button(106)),
            CanonicalButton.BTN_LSTICK_X to listOf(
                InputBinding.Axis(
                    axis = 0, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK,
                ),
                InputBinding.Axis(
                    axis = 0, restingValue = 1f, activeMin = 0f, activeMax = -1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK,
                ),
            ),
            CanonicalButton.BTN_MENU to listOf(InputBinding.Button(318), InputBinding.Button(4), InputBinding.Button(110)),
        ),
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
        glyphStyle = GlyphStyle.entries.last(),
        excludeFromGameplay = true,
        source = MappingSource.USER_WIZARD,
        userEdited = true,
    )

    @Test
    fun `writes standard ra keys`() {
        val cfg = RetroArchCfgWriter.write(sampleMapping(), debugBuild = true)
        assertTrue(cfg.contains("input_driver = \"android\""))
        assertTrue(cfg.contains("input_device = \"8BitDo Pro 2\""))
        assertTrue(cfg.contains("input_device_display_name = \"Living Room Pad\""))
        assertTrue(cfg.contains("input_vendor_id = \"11720\""))
        assertTrue(cfg.contains("input_product_id = \"24582\""))
        assertTrue(cfg.contains("input_b_btn = \"96\""))
        assertTrue(cfg.contains("input_a_btn = \"97\""))
        assertTrue(cfg.contains("input_up_btn = \"h0up\""))
        // BTN_L2 carries android axis 23 (AXIS_BRAKE), which is RA slot 8.
        assertTrue(cfg.contains("input_l2_axis = \"+8\""))
        assertTrue(cfg.contains("input_l3_btn = \"106\""))
        assertTrue(cfg.contains("input_l_x_plus_axis = \"+0\""))
        assertTrue(cfg.contains("input_l_x_minus_axis = \"-0\""))
        assertTrue(cfg.contains("input_menu_toggle_btn = \"318\""))
        assertTrue(cfg.contains("cannoli_source = \"USER\""))
        assertTrue(cfg.contains("cannoli_exclude_from_gameplay = \"true\""))
    }

    @Test
    fun `menu key skips injected platform defaults`() {
        val mapping = sampleMapping()
        val onlyDefaults = mapping.copy(
            bindings = mapping.bindings + (CanonicalButton.BTN_MENU to listOf(InputBinding.Button(4), InputBinding.Button(110)))
        )
        val cfg = RetroArchCfgWriter.write(onlyDefaults, debugBuild = true)
        assertTrue(!cfg.contains("input_menu_toggle_btn"))
    }

    @Test
    fun `menu key skips injected platform defaults in a release build too`() {
        val mapping = sampleMapping()
        val onlyDefaults = mapping.copy(
            bindings = mapping.bindings + (CanonicalButton.BTN_MENU to listOf(InputBinding.Button(4), InputBinding.Button(110)))
        )
        val cfg = RetroArchCfgWriter.write(onlyDefaults, debugBuild = false)
        assertTrue(!cfg.contains("input_menu_toggle_btn"))
    }

    @Test
    fun `a hat bound to the menu never claims the menu toggle key`() {
        val mapping = sampleMapping()
        val cfg = RetroArchCfgWriter.write(
            mapping.copy(
                bindings = mapping.bindings +
                    (CanonicalButton.BTN_MENU to listOf(InputBinding.Hat(16, HatDirection.UP)))
            ),
            debugBuild = true,
        )
        assertTrue(!cfg.contains("input_menu_toggle_btn"))
    }

    @Test
    fun `a release build nulls a real user-bound menu button`() {
        val cfg = RetroArchCfgWriter.write(sampleMapping(), debugBuild = false)
        assertTrue(cfg.contains("input_menu_toggle_btn = \"nul\""))
    }

    @Test
    fun `a debug build emits the real menu button`() {
        val cfg = RetroArchCfgWriter.write(sampleMapping(), debugBuild = true)
        assertTrue(cfg.contains("input_menu_toggle_btn = \"318\""))
    }

    @Test
    fun `quotes are stripped from emitted values`() {
        val cfg = RetroArchCfgWriter.write(sampleMapping().copy(displayName = """Brandon"s Pad"""))
        assertTrue(cfg.contains("""input_device_display_name = "Brandons Pad""""))
        val entry = RetroArchCfgParser.parse(cfg, fileName = "8BitDo_Pro_2.cfg")
        assertEquals("Brandons Pad", entry.displayName)
    }

    @Test
    fun `user mapping records the exact menu keycodes`() {
        val mapping = sampleMapping()
        val cfg = RetroArchCfgWriter.write(
            mapping.copy(
                bindings = mapping.bindings + (CanonicalButton.BTN_MENU to listOf(InputBinding.Button(318), InputBinding.Button(4)))
            )
        )
        assertTrue(cfg.contains("cannoli_menu_keycodes = \"318,4\""))
    }

    @Test
    fun `user mapping records a cleared menu as an empty keycode list`() {
        val mapping = sampleMapping()
        val cfg = RetroArchCfgWriter.write(
            mapping.copy(bindings = mapping.bindings + (CanonicalButton.BTN_MENU to emptyList()))
        )
        assertTrue(cfg.contains("cannoli_menu_keycodes = \"\""))
    }

    @Test
    fun `bundled mapping omits the menu keycodes`() {
        val cfg = RetroArchCfgWriter.write(
            sampleMapping().copy(source = MappingSource.RETROARCH_AUTOCONFIG, userEdited = false)
        )
        assertTrue(!cfg.contains("cannoli_menu_keycodes"))
    }

    @Test
    fun `a cleared menu survives write parse and import`() {
        val mapping = sampleMapping()
        val cleared = mapping.copy(bindings = mapping.bindings + (CanonicalButton.BTN_MENU to emptyList()))
        val entry = RetroArchCfgParser.parse(RetroArchCfgWriter.write(cleared), fileName = "8BitDo_Pro_2.cfg")
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "abc123", name = "8BitDo Pro 2",
            vendorId = 11720, productId = 24582, androidBuildModel = "",
            sourceMask = 0, connectedAtMillis = 0L,
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(emptyList<InputBinding>(), imported.bindings[CanonicalButton.BTN_MENU].orEmpty())
    }

    @Test
    fun `an edited menu survives write parse and import`() {
        val mapping = sampleMapping()
        val edited = mapping.copy(
            bindings = mapping.bindings + (CanonicalButton.BTN_MENU to listOf(InputBinding.Button(318)))
        )
        val entry = RetroArchCfgParser.parse(
            RetroArchCfgWriter.write(edited, debugBuild = true),
            fileName = "8BitDo_Pro_2.cfg",
        )
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "abc123", name = "8BitDo Pro 2",
            vendorId = 11720, productId = 24582, androidBuildModel = "",
            sourceMask = 0, connectedAtMillis = 0L,
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(listOf(InputBinding.Button(318)), imported.bindings[CanonicalButton.BTN_MENU])
    }

    @Test
    fun `two digital button axis bindings on one canonical emit only the first axis line`() {
        val mapping = sampleMapping()
        val cfg = RetroArchCfgWriter.write(
            mapping.copy(
                bindings = mapping.bindings + (
                    CanonicalButton.BTN_L2 to listOf(
                        InputBinding.Axis(
                            axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                            digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                        ),
                        InputBinding.Axis(
                            axis = 23, restingValue = 0f, activeMin = 0f, activeMax = 1f,
                            digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                        ),
                    )
                )
            )
        )
        // The first binding wins: android axis 17 (AXIS_LTRIGGER) is RA slot 6.
        val lines = cfg.lineSequence().filter { it.startsWith("input_l2_axis") }.toList()
        assertEquals(listOf("input_l2_axis = \"+6\""), lines)
    }

    @Test
    fun `stick plus and minus axis bindings on one canonical both still emit`() {
        val cfg = RetroArchCfgWriter.write(sampleMapping())
        assertTrue(cfg.contains("input_l_x_plus_axis = \"+0\""))
        assertTrue(cfg.contains("input_l_x_minus_axis = \"-0\""))
    }

    @Test
    fun `round trips through the importer`() {
        val original = sampleMapping()
        val cfg = RetroArchCfgWriter.write(original)
        val entry = RetroArchCfgParser.parse(cfg, fileName = "8BitDo_Pro_2.cfg")
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "abc123", name = "8BitDo Pro 2",
            vendorId = 11720, productId = 24582, androidBuildModel = "",
            sourceMask = 0, connectedAtMillis = 0L,
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(original.bindings[CanonicalButton.BTN_SOUTH], imported.bindings[CanonicalButton.BTN_SOUTH])
        assertEquals(original.bindings[CanonicalButton.BTN_UP], imported.bindings[CanonicalButton.BTN_UP])
        assertEquals(original.bindings[CanonicalButton.BTN_L2], imported.bindings[CanonicalButton.BTN_L2])
        assertEquals(original.bindings[CanonicalButton.BTN_L3], imported.bindings[CanonicalButton.BTN_L3])
        assertEquals(original.bindings[CanonicalButton.BTN_LSTICK_X], imported.bindings[CanonicalButton.BTN_LSTICK_X])
        assertEquals(original.menuConfirm, imported.menuConfirm)
        assertEquals(original.glyphStyle, imported.glyphStyle)
        assertEquals(original.excludeFromGameplay, imported.excludeFromGameplay)
        assertEquals(original.userEdited, imported.userEdited)
        assertEquals("8BitDo_Pro_2", imported.id)
    }

    @Test
    fun `all four stick axis key pairs round trip exactly through import and write`() {
        val cfg = """
            input_driver = "android"
            input_l_x_plus_axis = "+0"
            input_l_x_minus_axis = "-0"
            input_l_y_plus_axis = "+1"
            input_l_y_minus_axis = "-1"
            input_r_x_plus_axis = "+2"
            input_r_x_minus_axis = "-2"
            input_r_y_plus_axis = "+3"
            input_r_y_minus_axis = "-3"
            input_l2_axis = "+8"
            input_r2_axis = "+9"
        """.trimIndent()
        val entry = RetroArchCfgParser.parse(cfg, fileName = "Pad.cfg")
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "d", name = "Pad",
            vendorId = 0, productId = 0, androidBuildModel = "",
            sourceMask = 0, connectedAtMillis = 0L,
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device)
        val written = RetroArchCfgWriter.write(imported)

        // Proves slot -> android -> slot is a clean identity for every stick and trigger key.
        assertTrue(written.contains("input_l_x_plus_axis = \"+0\""))
        assertTrue(written.contains("input_l_x_minus_axis = \"-0\""))
        assertTrue(written.contains("input_l_y_plus_axis = \"+1\""))
        assertTrue(written.contains("input_l_y_minus_axis = \"-1\""))
        assertTrue(written.contains("input_r_x_plus_axis = \"+2\""))
        assertTrue(written.contains("input_r_x_minus_axis = \"-2\""))
        assertTrue(written.contains("input_r_y_plus_axis = \"+3\""))
        assertTrue(written.contains("input_r_y_minus_axis = \"-3\""))
        assertTrue(written.contains("input_l2_axis = \"+8\""))
        assertTrue(written.contains("input_r2_axis = \"+9\""))
    }

    @Test fun `writes source USER for an edited mapping and omits legacy keys`() {
        val text = RetroArchCfgWriter.write(sampleMapping().copy(userEdited = true), false)
        assertEquals(true, text.contains("cannoli_source = \"USER\""))
        assertEquals(false, text.contains("cannoli_user"))
        assertEquals(false, text.contains("cannoli_descriptor"))
    }

    @Test fun `writes source INPUT_DB for an unedited mapping`() {
        val text = RetroArchCfgWriter.write(sampleMapping().copy(userEdited = false), false)
        assertEquals(true, text.contains("cannoli_source = \"INPUT_DB\""))
    }

    @Test
    fun `axis-reported dpad direction writes a signed axis value`() {
        val mapping = sampleMapping().copy(
            bindings = sampleMapping().bindings + (
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Axis(
                        axis = 1, restingValue = 0f, activeMin = 0f, activeMax = -1f,
                        digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                    )
                )
            )
        )
        val cfg = RetroArchCfgWriter.write(mapping)
        assertTrue(cfg.contains("input_up_axis = \"-1\""))
        assertFalse(cfg.contains("input_up_btn = \"-1\""))
    }

    @Test
    fun `a canonical carrying both a digital and an axis binding writes each key once`() {
        val mapping = sampleMapping().copy(
            bindings = sampleMapping().bindings + (
                // Axis listed before the Hat, to prove the digital binding wins the btn key
                // regardless of order, while the axis key still writes alongside it.
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Axis(
                        axis = 1, restingValue = 0f, activeMin = 0f, activeMax = -1f,
                        digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                    ),
                    InputBinding.Hat(16, HatDirection.UP),
                )
            )
        )
        val cfg = RetroArchCfgWriter.write(mapping)
        val btnLines = cfg.lineSequence().filter { it.startsWith("input_up_btn") }.toList()
        val axisLines = cfg.lineSequence().filter { it.startsWith("input_up_axis") }.toList()
        assertEquals(listOf("input_up_btn = \"h0up\""), btnLines)
        assertEquals(listOf("input_up_axis = \"-1\""), axisLines)
    }

    @Test
    fun `axis-reported dpad binding round trips through write parse and import`() {
        val original = sampleMapping().copy(
            bindings = sampleMapping().bindings + (
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Axis(
                        axis = 1, restingValue = 0f, activeMin = 0f, activeMax = -1f,
                        digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                    )
                )
            )
        )
        val cfg = RetroArchCfgWriter.write(original)
        assertTrue(cfg.contains("input_up_axis = \"-1\""))
        assertFalse(cfg.contains("input_up_btn = \"-1\""))
        val entry = RetroArchCfgParser.parse(cfg, fileName = "8BitDo_Pro_2.cfg")
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "abc123", name = "8BitDo Pro 2",
            vendorId = 11720, productId = 24582, androidBuildModel = "",
            sourceMask = 0, connectedAtMillis = 0L,
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device)
        val upBinding = imported.bindings[CanonicalButton.BTN_UP]?.single() as InputBinding.Axis
        assertEquals(1, upBinding.axis)
        assertEquals(-1f, upBinding.activeMax, 0.001f)
    }

    @Test
    fun `android axis ids translate to ra slot numbers on write`() {
        val mapping = sampleMapping().copy(
            bindings = mapOf(
                CanonicalButton.BTN_LSTICK_X to listOf(
                    InputBinding.Axis(
                        axis = 0, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                        digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK,
                    )
                ),
                CanonicalButton.BTN_RSTICK_X to listOf(
                    InputBinding.Axis(
                        axis = 11, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                        digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK,
                    )
                ),
                CanonicalButton.BTN_L2 to listOf(
                    InputBinding.Axis(
                        axis = 23, restingValue = 0f, activeMin = 0f, activeMax = 1f,
                        digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                    )
                ),
            ),
        )
        val cfg = RetroArchCfgWriter.write(mapping)
        assertTrue(cfg.contains("input_l_x_plus_axis = \"+0\""))
        assertTrue(cfg.contains("input_r_x_plus_axis = \"+2\""))
        assertTrue(cfg.contains("input_l2_axis = \"+8\""))
    }

    @Test
    fun `writes the device alias list`() {
        val base = sampleMapping()
        val mapping = base.copy(match = base.match.copy(aliases = listOf("8BitDo Pro 2 Keyboard")))
        assertTrue(
            RetroArchCfgWriter.write(mapping, debugBuild = true)
                .contains("cannoli_device_aliases = \"8BitDo Pro 2 Keyboard\"")
        )
    }

    @Test
    fun `omits the device alias key when there are none`() {
        assertFalse(RetroArchCfgWriter.write(sampleMapping(), debugBuild = true).contains("cannoli_device_aliases"))
    }
}