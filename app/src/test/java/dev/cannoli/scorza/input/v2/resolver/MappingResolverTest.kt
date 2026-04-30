package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.MappingSource
import dev.cannoli.scorza.input.v2.repo.MappingRepository
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

    private val defaultHints = dev.cannoli.scorza.input.v2.hints.ControllerHintTable.fromJson(
        """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
    )

    private fun makeRepo() = MappingRepository(tempFolder.root)

    private fun makeResolver(
        repo: MappingRepository,
        bundledRa: List<RetroArchCfgEntry> = emptyList(),
    ) = MappingResolver(repo, bundledRa, defaultHints, tempFolder.root)

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

}
