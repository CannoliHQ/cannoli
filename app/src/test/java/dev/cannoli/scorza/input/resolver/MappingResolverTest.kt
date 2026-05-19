package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.repo.MappingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MappingResolverTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val device = ConnectedDevice(
        androidDeviceId = 7,
        descriptor = "abc",
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        androidBuildModel = "Pixel",
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    private val defaultHints = dev.cannoli.scorza.input.hints.ControllerHintTable.fromJson(
        """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
    )

    private fun makeRepo() = MappingRepository(tempFolder.root)

    private fun makeResolver(
        repo: MappingRepository,
        bundledRa: List<RetroArchCfgEntry> = emptyList(),
    ) = MappingResolver(
        repo,
        dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries.forTest(bundledRa),
        defaultHints,
        tempFolder.root,
    )

    @Test
    fun returns_existing_on_disk_template_when_match_rule_scores_above_zero() {
        val repo = makeRepo()
        val saved = DeviceMapping(
            id = "stadia_user",
            displayName = "Stadia (user)",
            match = DeviceMatchRule(vendorId = 6353, productId = 37888),
            bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
            source = MappingSource.USER_WIZARD,
            userEdited = true,
        )
        repo.save(saved)

        val resolved = makeResolver(repo).resolve(device)

        assertEquals("stadia_user", resolved.mapping.id)
        assertTrue(resolved.persistent)
    }

    @Test
    fun on_score_tie_picks_most_recently_edited_template() {
        val repo = makeRepo()
        repo.save(
            DeviceMapping(
                id = "older",
                displayName = "older",
                match = DeviceMatchRule(name = "Stadia Controller"),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = MappingSource.RETROARCH_AUTOCONFIG,
            )
        )
        Thread.sleep(20)
        repo.save(
            DeviceMapping(
                id = "newer",
                displayName = "newer",
                match = DeviceMatchRule(name = "Stadia Controller"),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = MappingSource.RETROARCH_AUTOCONFIG,
            )
        )

        val resolved = makeResolver(repo).resolve(device)
        assertEquals("newer", resolved.mapping.id)
    }

    @Test
    fun no_disk_match_falls_through_to_ra_autoconfig_when_bundled_entry_matches() {
        val repo = makeRepo()
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )
        val resolved = makeResolver(repo, bundledRa = ra).resolve(device)
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.mapping.source)
        assertFalse(resolved.persistent)
        assertEquals(0, repo.list().size)
    }

    @Test
    fun nothing_matches_yields_runtime_android_default_not_persisted() {
        val repo = makeRepo()
        val resolved = makeResolver(repo).resolve(device)
        assertEquals(MappingSource.ANDROID_DEFAULT, resolved.mapping.source)
        assertFalse(resolved.persistent)
        assertEquals(0, repo.list().size)
    }

    @Test
    fun resolver_priority_is_disk_then_ra_then_default() {
        val repo = makeRepo()
        val saved = DeviceMapping(
            id = "disk_wins",
            displayName = "Disk wins",
            match = DeviceMatchRule(vendorId = 6353, productId = 37888),
            bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
        repo.save(saved)
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )
        val resolved = makeResolver(repo, bundledRa = ra).resolve(device)
        assertEquals("disk_wins", resolved.mapping.id)
    }

    // Retroid handhelds rewrite a paired BT pad's gamepad endpoint to report the built-in's
    // VID/PID while keeping the BT pad's actual name. The cfg whose name matches the device must
    // win over the cfg whose VID/PID happens to match -- otherwise the DualSense inherits the
    // built-in's button layout and the saved file's [match] block gets the built-in's identity.
    @Test
    fun phantom_rewrite_prefers_name_matching_cfg_over_vidpid_matching_cfg() {
        val repo = makeRepo()
        val phantomDualSense = ConnectedDevice(
            androidDeviceId = 11,
            descriptor = "c575e892a6bb353df4b1327e81beedf84b540eb4",
            name = "DualSense Wireless Controller",
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "Retroid Pocket Classic",
            sourceMask = 0,
            connectedAtMillis = 0L,
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
        val resolved = makeResolver(repo, bundledRa = ra).resolve(phantomDualSense)
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, resolved.mapping.source)
        assertEquals("DualSense Wireless Controller", resolved.mapping.match.name)
        assertEquals(1356, resolved.mapping.match.vendorId)
        assertEquals(3302, resolved.mapping.match.productId)
    }

    @Test
    fun saved_mapping_still_wins_when_descriptor_changes_across_reconnect() {
        // First connect: user saves a mapping. Mapping's DeviceMatchRule captures the current
        // descriptor along with name+VID/PID. Later, Android rotates the descriptor (BT nonce flip,
        // phantom rewrite, or simply a fresh InputDevice id for the same physical pad). The saved
        // mapping must still resolve.
        val repo = makeRepo()
        val saved = DeviceMapping(
            id = "stadia_user",
            displayName = "Stadia (user)",
            match = DeviceMatchRule(
                name = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                descriptor = "first-session-descriptor",
            ),
            bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
            source = MappingSource.USER_WIZARD,
            userEdited = true,
        )
        repo.save(saved)

        val reconnect = device.copy(descriptor = "second-session-descriptor")
        val resolved = makeResolver(repo).resolve(reconnect)

        assertEquals("stadia_user", resolved.mapping.id)
        assertTrue(resolved.persistent)
    }

    @Test
    fun two_same_model_pads_both_resolve_to_same_saved_mapping() {
        // Two physically distinct Pro pads of the same make/model. Both score 150 against the
        // saved mapping (name +50, VID/PID +100) regardless of descriptor. After the descriptor
        // demotion, either pad's connect resolves to the same template.
        val repo = makeRepo()
        repo.save(
            DeviceMapping(
                id = "switch_pro_user",
                displayName = "Switch Pro (user)",
                match = DeviceMatchRule(
                    name = "Nintendo Switch Pro Controller",
                    vendorId = 1406,
                    productId = 8201,
                    descriptor = "first-pad-descriptor",
                ),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = MappingSource.USER_WIZARD,
                userEdited = true,
            )
        )

        val padOne = ConnectedDevice(
            androidDeviceId = 7,
            descriptor = "first-pad-descriptor",
            name = "Nintendo Switch Pro Controller",
            vendorId = 1406,
            productId = 8201,
            androidBuildModel = "Pixel",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        val padTwo = padOne.copy(androidDeviceId = 8, descriptor = "second-pad-descriptor")

        val resolver = makeResolver(repo)
        assertEquals("switch_pro_user", resolver.resolve(padOne).mapping.id)
        assertEquals("switch_pro_user", resolver.resolve(padTwo).mapping.id)
    }

    @Test
    fun saved_mapping_resolves_for_bluetooth_pad_with_zero_vid_pid() {
        // Common BT controller failure mode: kernel reports VID 0, PID 0. The saved mapping must
        // still resolve via name alone.
        val repo = makeRepo()
        repo.save(
            DeviceMapping(
                id = "bt_pad_user",
                displayName = "Bluetooth Gamepad (user)",
                match = DeviceMatchRule(
                    name = "Bluetooth Gamepad",
                    vendorId = null,
                    productId = null,
                ),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = MappingSource.USER_WIZARD,
                userEdited = true,
            )
        )

        val btPad = ConnectedDevice(
            androidDeviceId = 9,
            descriptor = "some-bt-descriptor",
            name = "Bluetooth Gamepad",
            vendorId = 0,
            productId = 0,
            androidBuildModel = "Pixel",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )

        val resolved = makeResolver(repo).resolve(btPad)
        assertEquals("bt_pad_user", resolved.mapping.id)
        assertTrue(resolved.persistent)
    }

}
