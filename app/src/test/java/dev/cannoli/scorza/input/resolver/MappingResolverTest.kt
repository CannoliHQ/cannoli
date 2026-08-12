package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgParser
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MappingResolverTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun device(
        androidDeviceId: Int = 7,
        descriptor: String = "abc",
        name: String = "Stadia Controller",
        vendorId: Int = 6353,
        productId: Int = 37888,
        androidBuildModel: String = "Pixel",
    ) = ConnectedDevice(
        androidDeviceId = androidDeviceId,
        descriptor = descriptor,
        name = name,
        vendorId = vendorId,
        productId = productId,
        androidBuildModel = androidBuildModel,
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    private fun diskRepo() = AutoconfigRepository { tmp.root }

    private fun resolver(
        bundled: List<RetroArchCfgEntry> = emptyList(),
    ) = MappingResolver(
        diskRepository = diskRepo(),
        bundledRetroArchEntries = BundledAutoconfigEntries.forTest(bundled),
    )

    private fun writeCfg(name: String, contents: String) =
        java.io.File(tmp.root, name).writeText(contents)

    @Test
    fun `disk user file beats bundled entry for the same pad`() {
        java.io.File(tmp.root, "PadA.cfg").writeText(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"96\"\n"
        )
        java.io.File(tmp.root, "PadA_user.cfg").writeText(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"97\"\ncannoli_user = \"true\"\n"
        )
        val resolved = resolver().resolve(device(name = "Pad A", vendorId = 1, productId = 2))
        assertTrue(resolved.userEdited)
        assertEquals(listOf(InputBinding.Button(97)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `asset entries are used when the disk dir is empty`() {
        val bundled = RetroArchCfgParser.parse(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"96\"\n"
        )
        val resolved = resolver(bundled = listOf(bundled)).resolve(device(name = "Pad A", vendorId = 1, productId = 2))
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.source)
        assertEquals(listOf(InputBinding.Button(96)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun returns_existing_disk_cfg_that_matches_on_vid_pid_alone() {
        writeCfg(
            "stadia_user.cfg",
            """
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            cannoli_user = "true"
            """.trimIndent()
        )

        val resolved = resolver().resolve(device())

        assertEquals("stadia_user", resolved.id)
        assertTrue(resolved.userEdited)
    }

    @Test
    fun user_cfg_carrying_the_device_descriptor_wins_over_another_user_cfg() {
        // Two user files describe the same make and model; only the descriptor tells the two
        // physical pads apart, so it is what picks the file.
        writeCfg(
            "other_pad_user.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            cannoli_descriptor = "other-pad"
            cannoli_user = "true"
            """.trimIndent()
        )
        writeCfg(
            "this_pad_user.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "97"
            cannoli_descriptor = "abc"
            cannoli_user = "true"
            """.trimIndent()
        )

        val resolved = resolver().resolve(device())

        assertEquals("this_pad_user", resolved.id)
    }

    @Test
    fun no_disk_match_falls_through_to_ra_autoconfig_when_bundled_entry_matches() {
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )
        val resolved = resolver(bundled = ra).resolve(device())
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.source)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun nothing_matches_yields_runtime_android_default_not_persisted() {
        val resolved = resolver().resolve(device())
        assertEquals(MappingSource.ANDROID_DEFAULT, resolved.source)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun resolver_priority_is_disk_then_ra_then_default() {
        writeCfg(
            "disk_wins.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            """.trimIndent()
        )
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )
        val resolved = resolver(bundled = ra).resolve(device())
        assertEquals("disk_wins", resolved.id)
    }

    // Retroid handhelds rewrite a paired BT pad's gamepad endpoint to report the built-in's
    // VID/PID while keeping the BT pad's actual name. The cfg whose name matches the device must
    // win over the cfg whose VID/PID happens to match -- otherwise the DualSense inherits the
    // built-in's button layout and the saved file's [match] block gets the built-in's identity.
    @Test
    fun phantom_rewrite_prefers_name_matching_cfg_over_vidpid_matching_cfg() {
        val phantomDualSense = device(
            androidDeviceId = 11,
            descriptor = "c575e892a6bb353df4b1327e81beedf84b540eb4",
            name = "DualSense Wireless Controller",
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "Retroid Pocket Classic",
        )
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Retroid Pocket Controller",
                vendorId = 8226, productId = 12289,
                buttonBindings = mapOf("a_btn" to 96),
            ),
            RetroArchCfgEntry(
                deviceName = "DualSense Wireless Controller",
                vendorId = 1356, productId = 3302,
                buttonBindings = mapOf("b_btn" to 96, "a_btn" to 97),
            ),
        )
        val resolved = resolver(bundled = ra).resolve(phantomDualSense)
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.source)
        assertEquals("DualSense Wireless Controller", resolved.match.name)
        assertEquals(1356, resolved.match.vendorId)
        assertEquals(3302, resolved.match.productId)
    }

    @Test
    fun user_cfg_still_wins_when_descriptor_changes_across_reconnect() {
        // First connect: the user's edits are written under the descriptor of that session. Later,
        // Android rotates the descriptor (BT nonce flip, phantom rewrite, or simply a fresh
        // InputDevice id for the same physical pad). The user's file must still resolve.
        writeCfg(
            "stadia_user.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            cannoli_descriptor = "first-session-descriptor"
            cannoli_user = "true"
            """.trimIndent()
        )

        val resolved = resolver().resolve(device(descriptor = "second-session-descriptor"))

        assertEquals("stadia_user", resolved.id)
        assertTrue(resolved.userEdited)
    }

    @Test
    fun two_same_model_pads_both_resolve_to_same_user_cfg() {
        // Two physically distinct Pro pads of the same make/model. The file names one pad's
        // descriptor, so the other pad falls back to name + VID/PID and lands on the same file.
        writeCfg(
            "switch_pro_user.cfg",
            """
            input_device = "Nintendo Switch Pro Controller"
            input_vendor_id = "1406"
            input_product_id = "8201"
            input_b_btn = "96"
            cannoli_descriptor = "first-pad-descriptor"
            cannoli_user = "true"
            """.trimIndent()
        )

        val padOne = device(
            descriptor = "first-pad-descriptor",
            name = "Nintendo Switch Pro Controller",
            vendorId = 1406,
            productId = 8201,
        )
        val padTwo = padOne.copy(androidDeviceId = 8, descriptor = "second-pad-descriptor")

        assertEquals("switch_pro_user", resolver().resolve(padOne).id)
        assertEquals("switch_pro_user", resolver().resolve(padTwo).id)
    }

    @Test
    fun user_cfg_resolves_for_bluetooth_pad_with_zero_vid_pid() {
        // Common BT controller failure mode: kernel reports VID 0, PID 0. The user's file must
        // still resolve via name alone.
        writeCfg(
            "bt_pad_user.cfg",
            """
            input_device = "Bluetooth Gamepad"
            input_b_btn = "96"
            cannoli_user = "true"
            """.trimIndent()
        )

        val btPad = device(
            androidDeviceId = 9,
            descriptor = "some-bt-descriptor",
            name = "Bluetooth Gamepad",
            vendorId = 0,
            productId = 0,
        )

        val resolved = resolver().resolve(btPad)

        assertEquals("bt_pad_user", resolved.id)
        assertTrue(resolved.userEdited)
    }

    @Test
    fun user_cfg_outranks_bundled_ra_cfg() {
        // User customization must always beat the bundled cfg, even when both match. Without this
        // guarantee, a user's edits would be silently reverted by an update that ships a new
        // bundled cfg.
        writeCfg(
            "stadia_user_custom.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            cannoli_user = "true"
            """.trimIndent()
        )
        val raEntry = RetroArchCfgEntry(
            deviceName = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
            buttonBindings = mapOf("a_btn" to 96, "b_btn" to 97),
        )

        val resolved = resolver(bundled = listOf(raEntry)).resolve(device())

        assertEquals("stadia_user_custom", resolved.id)
        assertTrue(resolved.userEdited)
    }

    @Test
    fun cfg_vid_pid_drives_hint_lookup_over_phantom_device_identity() {
        // Phantom-rewrite hosts (Retroid family) report a BT pad's gamepad endpoint with the
        // built-in's VID/PID while keeping the pad's real name. The bundled cfg matches by
        // name, but its header carries the pad's true VID. Hint lookup must prefer the cfg's
        // VID/PID over the device's reported (phantom) VID/PID so a DualSense connected via
        // such a host doesn't inherit the host's hint (e.g. Nintendo BTN_EAST/PLUMBER).
        val raEntry = RetroArchCfgEntry(
            deviceName = "DualSense Wireless Controller",
            vendorId = 1356,
            productId = 3302,
            buttonBindings = mapOf("a_btn" to 96, "b_btn" to 97),
        )
        val phantomDualsense = device(
            androidDeviceId = 11,
            descriptor = "phantom-bt",
            name = "DualSense Wireless Controller",
            // Phantom-rewritten to the Retroid built-in's VID/PID instead of Sony's.
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "Retroid Pocket Classic",
        )

        val resolved = resolver(bundled = listOf(raEntry))
            .resolve(phantomDualsense)

        assertEquals(CanonicalButton.BTN_SOUTH, resolved.menuConfirm)
        assertEquals(GlyphStyle.SHAPES, resolved.glyphStyle)
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.source)
    }

    @Test
    fun user_cfg_cosmetic_choices_survive_when_no_bundled_cfg_matches() {
        // The user's cosmetic toggles (glyph style, menu confirm side, display name) live in the
        // same file as their bindings, so they come back on the next resolve without a bundled
        // cfg being involved at all.
        writeCfg(
            "stadia_user_cosmetic.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            input_device_display_name = "Stadia"
            cannoli_glyph_style = "REDMOND"
            cannoli_user = "true"
            """.trimIndent()
        )

        val resolved = resolver().resolve(device())

        assertEquals("stadia_user_cosmetic", resolved.id)
        assertEquals("Stadia", resolved.displayName)
        assertEquals(GlyphStyle.REDMOND, resolved.glyphStyle)
    }

    // Some shared-identity pads (e.g. the AYN Thor / Portal / Odin 3 all reporting "Odin
    // Controller" 0x2020:0x0111) cannot be told apart by name+vid/pid at all. A cfg pinned to
    // Build.MODEL disambiguates them: it is exclusive to that one device.
    @Test
    fun `build model cfg wins over a same name vid pid cfg when device model matches`() {
        writeCfg(
            "odin_portal.cfg",
            """
            input_device = "Odin Controller"
            input_vendor_id = "8224"
            input_product_id = "273"
            input_b_btn = "96"
            """.trimIndent()
        )
        writeCfg(
            "odin_thor.cfg",
            """
            input_device = "Odin Controller"
            input_vendor_id = "8224"
            input_product_id = "273"
            input_b_btn = "97"
            cannoli_build_model = "AYN Thor"
            """.trimIndent()
        )

        val resolved = resolver().resolve(
            device(name = "Odin Controller", vendorId = 8224, productId = 273, androidBuildModel = "AYN Thor")
        )

        assertEquals("odin_thor", resolved.id)
        assertEquals(listOf(InputBinding.Button(97)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `build model cfg is skipped for a different device model, which resolves to the other cfg`() {
        writeCfg(
            "odin_portal.cfg",
            """
            input_device = "Odin Controller"
            input_vendor_id = "8224"
            input_product_id = "273"
            input_b_btn = "96"
            """.trimIndent()
        )
        writeCfg(
            "odin_thor.cfg",
            """
            input_device = "Odin Controller"
            input_vendor_id = "8224"
            input_product_id = "273"
            input_b_btn = "97"
            cannoli_build_model = "AYN Thor"
            """.trimIndent()
        )

        val resolved = resolver().resolve(
            device(name = "Odin Controller", vendorId = 8224, productId = 273, androidBuildModel = "AYN Odin Portal")
        )

        assertEquals("odin_portal", resolved.id)
        assertEquals(listOf(InputBinding.Button(96)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `build model cfg never matches a different device even as the sole name vid pid match`() {
        writeCfg(
            "odin_thor.cfg",
            """
            input_device = "Odin Controller"
            input_vendor_id = "8224"
            input_product_id = "273"
            input_b_btn = "97"
            cannoli_build_model = "AYN Thor"
            """.trimIndent()
        )

        val resolved = resolver().resolve(
            device(name = "Odin Controller", vendorId = 8224, productId = 273, androidBuildModel = "AYN Odin Portal")
        )

        assertEquals(MappingSource.ANDROID_DEFAULT, resolved.source)
    }

    // The build-model tier applies to bundled entries too, so a shared-identity pad (AYN Thor)
    // resolves correctly even before disk seeding lands.
    @Test
    fun `bundled build model cfg resolves when nothing is on disk`() {
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Odin Controller",
                vendorId = 8224, productId = 273,
                buttonBindings = mapOf("b_btn" to 96),
            ),
            RetroArchCfgEntry(
                deviceName = "Odin Controller",
                vendorId = 8224, productId = 273,
                buttonBindings = mapOf("b_btn" to 97),
                buildModel = "AYN Thor",
            ),
        )

        val resolved = resolver(bundled = ra).resolve(
            device(name = "Odin Controller", vendorId = 8224, productId = 273, androidBuildModel = "AYN Thor")
        )

        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.source)
        assertEquals(listOf(InputBinding.Button(97)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }
}
