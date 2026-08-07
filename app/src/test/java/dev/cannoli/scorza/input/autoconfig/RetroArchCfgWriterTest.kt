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
import dev.cannoli.scorza.input.hints.ControllerHintTable
import dev.cannoli.scorza.input.resolver.RetroArchAutoconfigImporter
import org.junit.Assert.assertEquals
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
            descriptor = "abc123",
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
            CanonicalButton.BTN_L3 to listOf(
                InputBinding.Button(106),
                InputBinding.Axis(
                    axis = 0, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.LEFT_STICK_X,
                ),
                InputBinding.Axis(
                    axis = 0, restingValue = 1f, activeMin = 0f, activeMax = -1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.LEFT_STICK_X,
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

    private val defaultHints = ControllerHintTable.fromJson(
        """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
    )

    @Test
    fun `writes standard ra keys`() {
        val cfg = RetroArchCfgWriter.write(sampleMapping())
        assertTrue(cfg.contains("input_driver = \"android\""))
        assertTrue(cfg.contains("input_device = \"8BitDo Pro 2\""))
        assertTrue(cfg.contains("input_device_display_name = \"Living Room Pad\""))
        assertTrue(cfg.contains("input_vendor_id = \"11720\""))
        assertTrue(cfg.contains("input_product_id = \"24582\""))
        assertTrue(cfg.contains("input_b_btn = \"96\""))
        assertTrue(cfg.contains("input_a_btn = \"97\""))
        assertTrue(cfg.contains("input_up_btn = \"h0up\""))
        assertTrue(cfg.contains("input_l2_axis = \"+23\""))
        assertTrue(cfg.contains("input_l3_btn = \"106\""))
        assertTrue(cfg.contains("input_l_x_plus_axis = \"+0\""))
        assertTrue(cfg.contains("input_l_x_minus_axis = \"-0\""))
        assertTrue(cfg.contains("input_menu_toggle_btn = \"318\""))
        assertTrue(cfg.contains("cannoli_user = \"true\""))
        assertTrue(cfg.contains("cannoli_exclude_from_gameplay = \"true\""))
        assertTrue(cfg.contains("cannoli_descriptor = \"abc123\""))
    }

    @Test
    fun `menu key skips injected platform defaults`() {
        val mapping = sampleMapping()
        val onlyDefaults = mapping.copy(
            bindings = mapping.bindings + (CanonicalButton.BTN_MENU to listOf(InputBinding.Button(4), InputBinding.Button(110)))
        )
        val cfg = RetroArchCfgWriter.write(onlyDefaults)
        assertTrue(!cfg.contains("input_menu_toggle_btn"))
    }

    @Test
    fun `a hat bound to the menu never claims the menu toggle key`() {
        val mapping = sampleMapping()
        val cfg = RetroArchCfgWriter.write(
            mapping.copy(
                bindings = mapping.bindings +
                    (CanonicalButton.BTN_MENU to listOf(InputBinding.Hat(16, HatDirection.UP)))
            )
        )
        assertTrue(!cfg.contains("input_menu_toggle_btn"))
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
        val imported = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
        assertEquals(emptyList<InputBinding>(), imported.bindings[CanonicalButton.BTN_MENU].orEmpty())
    }

    @Test
    fun `an edited menu survives write parse and import`() {
        val mapping = sampleMapping()
        val edited = mapping.copy(
            bindings = mapping.bindings + (CanonicalButton.BTN_MENU to listOf(InputBinding.Button(318)))
        )
        val entry = RetroArchCfgParser.parse(RetroArchCfgWriter.write(edited), fileName = "8BitDo_Pro_2.cfg")
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "abc123", name = "8BitDo Pro 2",
            vendorId = 11720, productId = 24582, androidBuildModel = "",
            sourceMask = 0, connectedAtMillis = 0L,
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
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
        val lines = cfg.lineSequence().filter { it.startsWith("input_l2_axis") }.toList()
        assertEquals(listOf("input_l2_axis = \"+17\""), lines)
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
        val imported = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
        assertEquals(original.bindings[CanonicalButton.BTN_SOUTH], imported.bindings[CanonicalButton.BTN_SOUTH])
        assertEquals(original.bindings[CanonicalButton.BTN_UP], imported.bindings[CanonicalButton.BTN_UP])
        assertEquals(original.bindings[CanonicalButton.BTN_L2], imported.bindings[CanonicalButton.BTN_L2])
        assertEquals(original.bindings[CanonicalButton.BTN_L3], imported.bindings[CanonicalButton.BTN_L3])
        assertEquals(original.menuConfirm, imported.menuConfirm)
        assertEquals(original.glyphStyle, imported.glyphStyle)
        assertEquals(original.excludeFromGameplay, imported.excludeFromGameplay)
        assertEquals(original.userEdited, imported.userEdited)
        assertEquals("8BitDo_Pro_2", imported.id)
    }
}