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

    private fun stagedResolver(
        staging: java.io.File,
        bundled: List<RetroArchCfgEntry> = emptyList(),
    ) = MappingResolver(
        diskRepository = AutoconfigRepository(
            stagingDirProvider = { staging },
            dirProvider = { tmp.root },
        ),
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
    fun two_colliding_user_cfgs_resolve_deterministically_by_filename() {
        // Per-model scoping means two user files describing the same make and model are no
        // longer told apart by physical unit, so the tie is broken by filename, giving a
        // deterministic pick rather than one that depends on filesystem listing order.
        writeCfg(
            "other_pad_user.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "96"
            cannoli_source = "USER"
            """.trimIndent()
        )
        writeCfg(
            "this_pad_user.cfg",
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "97"
            cannoli_source = "USER"
            """.trimIndent()
        )

        val resolved = resolver().resolve(device())

        assertEquals("other_pad_user", resolved.id)
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
        assertEquals(MappingSource.UNIDENTIFIED, resolved.source)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun disk_entry_resolves_without_ever_considering_a_matching_bundled_entry() {
        // Disk being non-empty excludes bundled entries from the candidate set entirely (see
        // non_empty_disk_set_with_no_match_does_not_fall_back_to_bundled_assets), so this only
        // proves a disk match resolves -- there is no priority contest against the bundled entry.
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

    @Test
    fun non_empty_disk_set_with_no_match_does_not_fall_back_to_bundled_assets() {
        // Ruling: falling back to assets when disk is non-empty would resurrect profiles the
        // seeder deliberately did not materialise for this handheld. The disk set here has an
        // entry, just not one for this device, and a bundled entry that would match is never
        // consulted.
        writeCfg(
            "other_pad.cfg",
            """
            input_device = "Some Other Pad"
            input_vendor_id = "1"
            input_product_id = "2"
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
        assertEquals(MappingSource.UNIDENTIFIED, resolved.source)
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
    fun user_cfg_on_disk_resolves_even_though_a_bundled_entry_also_matches() {
        // The user's edits survive a bundled cfg update, but not because the user entry outranks
        // it in a comparison -- disk being non-empty excludes the bundled entry from the
        // candidate set before any ranking happens.
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

        assertEquals(MappingSource.UNIDENTIFIED, resolved.source)
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

    // A build-model cfg is the built-in pad's, not the handheld's: an external controller plugged
    // into that handheld reports the host Build.MODEL but its own vid/pid, and must not inherit
    // the built-in cfg.
    @Test
    fun `build model cfg does not match an external pad that only shares the host model`() {
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
            device(name = "DualSense Wireless Controller", vendorId = 0x054C, productId = 0x0CE6, androidBuildModel = "AYN Thor")
        )

        assertEquals(MappingSource.UNIDENTIFIED, resolved.source)
    }

    @Test fun `phantom ds4 does not inherit the built-in cfg when it carries a model pin`() {
        val ds4OnClassic = device(
            name = "Wireless Controller",
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "Retroid Pocket Classic",
        )
        val bundled = listOf(
            RetroArchCfgEntry(
                deviceName = "Retroid Pocket Controller",
                vendorId = 8226, productId = 12289,
                buildModel = "Retroid Pocket Classic",
                builtin = true,
                buttonBindings = mapOf("a_btn" to 96),
                fileName = "retroid_classic.cfg",
            ),
            RetroArchCfgEntry(
                deviceName = "Wireless Controller",
                vendorId = 1356, productId = 2508,
                buttonBindings = mapOf("b_btn" to 96, "a_btn" to 97),
                fileName = "sony_ds4.cfg",
            ),
        )
        val resolved = resolver(bundled = bundled).resolve(ds4OnClassic)
        assertEquals("Wireless Controller", resolved.match.name)
    }

    @Test fun `user provenance outranks input db for the same pad`() {
        writeCfg(
            "sony_ds4.cfg",
            """
            input_device = "Wireless Controller"
            input_vendor_id = "1356"
            input_product_id = "2508"
            input_b_btn = "96"
            cannoli_source = "INPUT_DB"
            """.trimIndent()
        )
        writeCfg(
            "sony_ds4_mine.cfg",
            """
            input_device = "Wireless Controller"
            input_vendor_id = "1356"
            input_product_id = "2508"
            input_b_btn = "97"
            cannoli_source = "USER"
            """.trimIndent()
        )
        val resolved = resolver().resolve(device(name = "Wireless Controller", vendorId = 1356, productId = 2508))
        assertEquals(MappingSource.USER_WIZARD, resolved.source)
    }

    @Test fun `user provenance outranks input db even at a worse match rank`() {
        // The realistic case on the affected handhelds: a wizard-made USER cfg carries no model
        // pin (NAME_AND_VID_PID), while the curated INPUT_DB cfg is pinned to this device's model
        // (NAME_AND_MODEL, the better rank). Provenance must still decide it, not rank.
        writeCfg(
            "sony_ds4.cfg",
            """
            input_device = "Wireless Controller"
            input_vendor_id = "1356"
            input_product_id = "2508"
            input_b_btn = "96"
            cannoli_build_model = "Pixel"
            cannoli_source = "INPUT_DB"
            """.trimIndent()
        )
        writeCfg(
            "sony_ds4_mine.cfg",
            """
            input_device = "Wireless Controller"
            input_vendor_id = "1356"
            input_product_id = "2508"
            input_b_btn = "97"
            cannoli_source = "USER"
            """.trimIndent()
        )
        val resolved = resolver().resolve(device(name = "Wireless Controller", vendorId = 1356, productId = 2508))
        assertEquals(MappingSource.USER_WIZARD, resolved.source)
    }

    @Test fun `builtin agreement wins the tiebreak over an unfavorable filename`() {
        // Same provenance (neither is user-owned) and same rank (both NAME_AND_VID_PID), so only
        // builtin agreement can decide it. The agreeing entry's filename sorts after the
        // disagreeing one's, so a filename-only tiebreak would pick the wrong entry.
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                builtin = true,
                buttonBindings = mapOf("b_btn" to 96),
                fileName = "a_disagrees.cfg",
            ),
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                builtin = false,
                buttonBindings = mapOf("b_btn" to 97),
                fileName = "z_agrees.cfg",
            ),
        )
        val resolved = resolver(bundled = ra).resolve(device())
        assertEquals("z_agrees", resolved.id)
    }

    @Test fun `filename tiebreak picks the alphabetically first bundled entry regardless of list order`() {
        // Both entries are bundled, not on disk, so AutoconfigRepository's own filename sort
        // cannot be doing this work. The list itself is in the wrong order, so only the
        // resolver's own tiebreak can produce the alphabetically first result.
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                buttonBindings = mapOf("b_btn" to 97),
                fileName = "z_pad.cfg",
            ),
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
                fileName = "a_pad.cfg",
            ),
        )
        val resolved = resolver(bundled = ra).resolve(device())
        assertEquals("a_pad", resolved.id)
    }

    private val pocketTacoCfg = """
        input_device = "GameSir-Pocket 1"
        input_vendor_id = "13623"
        input_product_id = "4402"
        cannoli_source = "INPUT_DB"
        cannoli_device_aliases = "GameSir-Pocket 1 Keyboard"
        input_b_btn = "96"
    """.trimIndent() + "\n"

    @Test
    fun `a db entry alias resolves the pad when nothing claims the reported name`() {
        writeCfg("gamesir_pocket_taco.cfg", pocketTacoCfg)
        val resolved = resolver().resolve(device(name = "GameSir-Pocket 1 Keyboard", vendorId = 13623, productId = 4402))
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.source)
        assertEquals(listOf(InputBinding.Button(96)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `a user cfg claiming the reported name outranks an alias match`() {
        writeCfg("gamesir_pocket_taco.cfg", pocketTacoCfg)
        writeCfg(
            "android_default_gamesir_pocket_1_keyboard.cfg",
            """
            input_device = "GameSir-Pocket 1 Keyboard"
            input_vendor_id = "13623"
            input_product_id = "4402"
            cannoli_source = "USER"
            input_b_btn = "97"
            """.trimIndent() + "\n",
        )
        val resolved = resolver().resolve(device(name = "GameSir-Pocket 1 Keyboard", vendorId = 13623, productId = 4402))
        assertTrue(resolved.userEdited)
        assertEquals(listOf(InputBinding.Button(97)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `a staged cfg does not hide the shipped database from another pad`() {
        // Staging holds the one pad configured before the storage grant. Folding it into the disk
        // set would read as "the seeded database answers for everything" and send every other pad
        // to the unknown path with its own entry sitting unread in the assets.
        val staging = tmp.newFolder("staging")
        java.io.File(staging, "PadA.cfg").writeText(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"96\"\ncannoli_user = \"true\"\n"
        )
        val bundled = RetroArchCfgParser.parse(
            "input_device = \"Pad B\"\ninput_vendor_id = \"3\"\ninput_product_id = \"4\"\ninput_b_btn = \"97\"\n",
            fileName = "PadB.cfg",
        )
        val resolved = stagedResolver(staging, bundled = listOf(bundled))
            .resolve(device(name = "Pad B", vendorId = 3, productId = 4))
        assertEquals("Pad B", resolved.match.name)
    }

    @Test
    fun `a staged cfg resolves its own pad before the grant`() {
        val staging = tmp.newFolder("staging")
        java.io.File(staging, "PadA.cfg").writeText(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"99\"\ncannoli_user = \"true\"\n"
        )
        val resolved = stagedResolver(staging).resolve(device(name = "Pad A", vendorId = 1, productId = 2))
        assertTrue(resolved.userEdited)
        assertEquals(listOf(InputBinding.Button(99)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `a staged cfg outranks a bundled entry for the same pad`() {
        val staging = tmp.newFolder("staging")
        java.io.File(staging, "PadA.cfg").writeText(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"99\"\ncannoli_user = \"true\"\n"
        )
        val bundled = RetroArchCfgParser.parse(
            "input_device = \"Pad A\"\ninput_vendor_id = \"1\"\ninput_product_id = \"2\"\ninput_b_btn = \"96\"\n",
            fileName = "PadA_bundled.cfg",
        )
        val resolved = stagedResolver(staging, bundled = listOf(bundled))
            .resolve(device(name = "Pad A", vendorId = 1, productId = 2))
        assertEquals(listOf(InputBinding.Button(99)), resolved.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test
    fun `a pad matching nothing gets identity and no bindings, never a guessed layout`() {
        val resolved = resolver().resolve(device(name = "Some Unknown Pad", vendorId = 9, productId = 9))
        assertEquals(MappingSource.UNIDENTIFIED, resolved.source)
        assertTrue(resolved.bindings.isEmpty())
        assertEquals("Some Unknown Pad", resolved.displayName)
        assertEquals("Some Unknown Pad", resolved.match.name)
    }

    @Test
    fun `an unidentified pad is what sends the user to the wizard`() {
        val resolved = resolver().resolve(device(name = "Some Unknown Pad", vendorId = 9, productId = 9))
        assertTrue(dev.cannoli.scorza.input.legend.shouldRunLegendWizard(resolved))
    }
}
