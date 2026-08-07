package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.hints.ControllerHintTable
import dev.cannoli.scorza.input.resolver.RetroArchAutoconfigImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures are copied verbatim from app/src/main/assets/autoconfig/android/. The bundled database
// carries keys the model does not own (axis directionals, alt identity blocks, device type, labels)
// and RetroArch reads the same files the launcher rewrites, so a user edit must not drop them.
class RetroArchCfgPassthroughTest {

    // app/src/main/assets/autoconfig/android/8Bitdo_SN30_GP_BT.cfg
    private val axisDpadCfg = """
        # 8Bitdo SN30 GP              - https://www.8bitdo.com/     - https://www.8bitdo.com/sn30-gp/
        # Firmware v6.14              - https://support.8bitdo.com/ - https://support.8bitdo.com/firmware-updater.html

        input_driver = "android"
        input_device = "8BitDo SN30 gamepad"
        input_device_display_name = "8BitDo SN30 GP"

        # Hex vid:pid and Decimal vid:pid is shown in the "log_verbosity" window, enable "log_verbosity" in retroarch.cfg and run RetroArch.
        # Hex vid:pid = 2DC8:2862 -> Decimal vid:pid = 11720:10338
        input_vendor_id = "11720"
        input_product_id = "10338"

        input_b_btn = "97"
        input_y_btn = "100"
        input_select_btn = "109"
        input_start_btn = "108"
        input_a_btn = "96"
        input_x_btn = "99"
        input_l_btn = "102"
        input_r_btn = "103"

        input_b_btn_label = "B"
        input_y_btn_label = "Y"
        input_select_btn_label = "Select"
        input_start_btn_label = "Start"
        input_a_btn_label = "A"
        input_x_btn_label = "X"
        input_l_btn_label = "L"
        input_r_btn_label = "R"

        input_up_axis = "-1"
        input_down_axis = "+1"
        input_left_axis = "-0"
        input_right_axis = "+0"

        input_up_axis_label = "Dpad Up"
        input_down_axis_label = "Dpad Down"
        input_left_axis_label = "Dpad Left"
        input_right_axis_label = "Dpad Right"
    """.trimIndent()

    // app/src/main/assets/autoconfig/android/8BitDo_Pro2.cfg
    private val altIdentityCfg = """
        # 8BitDo Pro 2        - https://www.8bitdo.com/        - https://www.8bitdo.com/pro2/
        # Firmware v1.05        - https://support.8bitdo.com/        - https://support.8bitdo.com/firmware-updater.html
        # This is with the device started in Android (D-Input) mode.

        input_driver = "android"
        input_device = "8BitDo Pro 2"
        # on USB, input_device = "8BitDo 8BitDo Pro 2". See below.
        input_device_display_name = "8BitDo Pro 2"
        # Hex vid:pid and Decimal vid:pid is shown in the "log_verbosity" window, enable "log_verbosity" in retroarch.cfg and run RetroArch.
        # Hex vid:pid = 2DC8:6006 -> Decimal vid:pid = 11720:24582
        input_vendor_id = "11720"
        input_product_id = "24582"

        # Firmware before v1.05 has different PID depending on connection type.
        input_device_alt1 = "8BitDo Pro 2"
        input_device_display_name_alt1 = "8BitDo Pro 2 (old firmware, Bluetooth)"
        # Hex vid:pid = 2DC8:6103 -> Decimal vid:pid = 11720:24835
        input_vendor_id_alt1 = "11720"
        input_product_id_alt1 = "24835"

        # on USB, Android prepends the vendor to the product name, so 8BitDo twice.
        input_device_alt2 = "8BitDo 8BitDo Pro 2"
        input_device_display_name_alt2 = "8BitDo Pro 2 (old firmware, USB)"
        # Hex vid:pid = 2DC8:6003 -> Decimal vid:pid = 11720:24579
        input_vendor_id_alt2 = "11720"
        input_product_id_alt2 = "24579"

        input_b_btn = "97"
        input_y_btn = "100"
        input_select_btn = "109"
        input_start_btn = "108"
        input_up_btn = "h0up"
        input_down_btn = "h0down"
        input_left_btn = "h0left"
        input_right_btn = "h0right"
        input_a_btn = "96"
        input_x_btn = "99"
        input_l_btn = "102"
        input_r_btn = "103"
        input_l2_axis = "+6"
        input_r2_axis = "+7"
        input_l3_btn = "106"
        input_r3_btn = "107"
        input_l_x_plus_axis = "+0"
        input_l_x_minus_axis = "-0"
        input_l_y_plus_axis = "+1"
        input_l_y_minus_axis = "-1"
        input_r_x_plus_axis = "+2"
        input_r_x_minus_axis = "-2"
        input_r_y_plus_axis = "+3"
        input_r_y_minus_axis = "-3"
        input_menu_toggle_btn = "110"

        input_b_btn_label = "B"
        input_y_btn_label = "Y"
        input_select_btn_label = "Select"
        input_start_btn_label = "Start"
        input_up_btn_label = "D-Pad Up"
        input_down_btn_label = "D-Pad Down"
        input_left_btn_label = "D-Pad Left"
        input_right_btn_label = "D-Pad Right"
        input_a_btn_label = "A"
        input_x_btn_label = "X"
        input_l_btn_label = "L"
        input_r_btn_label = "R"
        input_l2_axis_label = "L2"
        input_r2_axis_label = "R2"
        input_l3_btn_label = "LS"
        input_r3_btn_label = "RS"
        input_l_x_plus_axis_label = "LS Right"
        input_l_x_minus_axis_label = "LS Left"
        input_l_y_plus_axis_label = "LS Down"
        input_l_y_minus_axis_label = "LS Up"
        input_r_x_plus_axis_label = "RS Right"
        input_r_x_minus_axis_label = "RS Left"
        input_r_y_plus_axis_label = "RS Down"
        input_r_y_minus_axis_label = "RS Up"
        input_menu_toggle_btn_label = "Home"
    """.trimIndent()

    // app/src/main/assets/autoconfig/android/NVIDIA_SHIELD_2019_Remote.cfg
    private val deviceTypeCfg = """
        # This controller is intended for menu navigation only
        # it will work fine with an upcoming version that will bind remotes
        # and other general purpose I/O devices to a dedicated input port

        input_device = "SHIELD 2019 Remote"
        input_device_display_name = "NVIDIA SHIELD 2019 Remote"
        input_driver = "android"
        input_device_type = "remote"

        input_vendor_id = "2389"
        input_product_id = "29207"

        input_b_btn = "23"
        input_select_btn = "90"
        input_start_btn = "85"
        input_up_btn = "19"
        input_down_btn = "20"
        input_left_btn = "21"
        input_right_btn = "22"
        input_a_btn = "4"
        input_menu_toggle_btn = "89"

        input_b_btn_label = "Back"
        input_select_btn_label = "Fast Forward"
        input_start_btn_label = "Play/Pause"
        input_up_btn_label = "Up"
        input_down_btn_label = "Down"
        input_left_btn_label = "Left"
        input_right_btn_label = "Right"
        input_a_btn_label = "Center"
        input_menu_toggle_btn_label = "Rewind"
    """.trimIndent()

    private val hints = ControllerHintTable.fromJson(
        """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
    )

    private fun device(name: String, vendorId: Int, productId: Int) = ConnectedDevice(
        androidDeviceId = 1,
        descriptor = "desc-1",
        name = name,
        vendorId = vendorId,
        productId = productId,
        androidBuildModel = "",
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    private fun importOf(cfg: String, fileName: String, device: ConnectedDevice): DeviceMapping =
        RetroArchAutoconfigImporter.import(
            RetroArchCfgParser.parse(cfg, fileName = fileName),
            device,
            hints,
        )

    // A cosmetic edit: the user flipped the confirm button and nothing else.
    private fun cosmeticEdit(mapping: DeviceMapping) = mapping.copy(
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
        userEdited = true,
    )

    private fun assertUnmanagedLinesPreserved(original: String, output: String) {
        val missing = original.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { line -> RetroArchCfgEntry.MANAGED_KEYS.none { key -> line.startsWith("$key ") } }
            .filterNot { output.contains(it) }
            .toList()
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `axis dpad binds and labels survive a cosmetic edit`() {
        val mapping = cosmeticEdit(
            importOf(axisDpadCfg, "8Bitdo_SN30_GP_BT.cfg", device("8BitDo SN30 gamepad", 11720, 10338))
        )
        val output = RetroArchCfgWriter.write(mapping)

        assertTrue(output.contains("""input_up_axis = "-1""""))
        assertTrue(output.contains("""input_down_axis = "+1""""))
        assertTrue(output.contains("""input_left_axis = "-0""""))
        assertTrue(output.contains("""input_right_axis = "+0""""))
        assertTrue(output.contains("""input_up_axis_label = "Dpad Up""""))
        assertTrue(output.contains("""input_down_axis_label = "Dpad Down""""))
        assertTrue(output.contains("""input_left_axis_label = "Dpad Left""""))
        assertTrue(output.contains("""input_right_axis_label = "Dpad Right""""))
        assertTrue(output.contains("""input_b_btn_label = "B""""))
        assertTrue(output.contains("""input_r_btn_label = "R""""))
        assertUnmanagedLinesPreserved(axisDpadCfg, output)

        // The managed rewrites still happen.
        assertTrue(output.contains("""input_driver = "android""""))
        assertTrue(output.contains("""input_device = "8BitDo SN30 gamepad""""))
        assertTrue(output.contains("""input_device_display_name = "8BitDo SN30 GP""""))
        assertTrue(output.contains("""input_vendor_id = "11720""""))
        assertTrue(output.contains("""input_b_btn = "97""""))
        assertTrue(output.contains("""cannoli_user = "true""""))
        assertTrue(output.contains("""cannoli_confirm_button = "BTN_SOUTH""""))
    }

    @Test
    fun `axis dpad cfg is stable across a second round trip`() {
        val device = device("8BitDo SN30 gamepad", 11720, 10338)
        val first = RetroArchCfgWriter.write(
            cosmeticEdit(importOf(axisDpadCfg, "8Bitdo_SN30_GP_BT.cfg", device))
        )
        val second = RetroArchCfgWriter.write(importOf(first, "8Bitdo_SN30_GP_BT.cfg", device))
        assertEquals(first, second)
    }

    @Test
    fun `alt identity blocks and labels survive a cosmetic edit`() {
        val mapping = cosmeticEdit(
            importOf(altIdentityCfg, "8BitDo_Pro2.cfg", device("8BitDo Pro 2", 11720, 24582))
        )
        val output = RetroArchCfgWriter.write(mapping)

        assertTrue(output.contains("""input_device_alt1 = "8BitDo Pro 2""""))
        assertTrue(output.contains("""input_device_display_name_alt1 = "8BitDo Pro 2 (old firmware, Bluetooth)""""))
        assertTrue(output.contains("""input_vendor_id_alt1 = "11720""""))
        assertTrue(output.contains("""input_product_id_alt1 = "24835""""))
        assertTrue(output.contains("""input_device_alt2 = "8BitDo 8BitDo Pro 2""""))
        assertTrue(output.contains("""input_device_display_name_alt2 = "8BitDo Pro 2 (old firmware, USB)""""))
        assertTrue(output.contains("""input_vendor_id_alt2 = "11720""""))
        assertTrue(output.contains("""input_product_id_alt2 = "24579""""))
        assertTrue(output.contains("""input_menu_toggle_btn_label = "Home""""))
        assertTrue(output.contains("""input_l_x_plus_axis_label = "LS Right""""))
        assertUnmanagedLinesPreserved(altIdentityCfg, output)
    }

    @Test
    fun `alt identity cfg is stable across a second round trip`() {
        val device = device("8BitDo Pro 2", 11720, 24582)
        val first = RetroArchCfgWriter.write(
            cosmeticEdit(importOf(altIdentityCfg, "8BitDo_Pro2.cfg", device))
        )
        val second = RetroArchCfgWriter.write(importOf(first, "8BitDo_Pro2.cfg", device))
        assertEquals(first, second)
    }

    @Test
    fun `device type survives while a cleared menu toggle does not pass through`() {
        val imported = importOf(deviceTypeCfg, "NVIDIA_SHIELD_2019_Remote.cfg", device("SHIELD 2019 Remote", 2389, 29207))
        val cleared = cosmeticEdit(
            imported.copy(bindings = imported.bindings + (CanonicalButton.BTN_MENU to emptyList()))
        )
        val output = RetroArchCfgWriter.write(cleared)

        assertTrue(output.contains("""input_device_type = "remote""""))
        assertTrue(output.contains("""input_menu_toggle_btn_label = "Rewind""""))
        assertFalse(output.contains("""input_menu_toggle_btn = """"))
        assertTrue(output.contains("""cannoli_menu_keycodes = """""))
        assertUnmanagedLinesPreserved(deviceTypeCfg, output)
    }

    @Test
    fun `an unquoted managed key is not passed through alongside the rewritten one`() {
        // Onn-Remote.cfg leaves the vendor and product ids unquoted, so the parser cannot read
        // them; they must still not survive as a second line for a key the writer owns.
        val unquoted = """
            input_driver = "android"
            input_device = "Onn-Remote"
            input_device_type = "remote"
            input_vendor_id = 2391
            input_product_id = 5
            input_b_btn = "23"
        """.trimIndent()
        val output = RetroArchCfgWriter.write(
            cosmeticEdit(importOf(unquoted, "Onn-Remote.cfg", device("Onn-Remote", 2391, 5)))
        )
        assertEquals(1, output.lineSequence().count { it.startsWith("input_vendor_id ") })
        assertEquals(1, output.lineSequence().count { it.startsWith("input_product_id ") })
        assertTrue(output.contains("""input_device_type = "remote""""))
    }

    @Test
    fun `device type cfg is stable across a second round trip`() {
        val device = device("SHIELD 2019 Remote", 2389, 29207)
        val imported = importOf(deviceTypeCfg, "NVIDIA_SHIELD_2019_Remote.cfg", device)
        val cleared = cosmeticEdit(
            imported.copy(bindings = imported.bindings + (CanonicalButton.BTN_MENU to emptyList()))
        )
        val first = RetroArchCfgWriter.write(cleared)
        val second = RetroArchCfgWriter.write(importOf(first, "NVIDIA_SHIELD_2019_Remote.cfg", device))
        assertEquals(first, second)
    }
}
