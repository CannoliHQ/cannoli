package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import dev.cannoli.scorza.input.v2.repo.TemplateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplateResolverTest {

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

    private fun makeRepo() = TemplateRepository(tempFolder.root)

    private fun makeResolver(
        repo: TemplateRepository,
        paddleboat: PaddleboatTemplateImporter = NoopPaddleboatTemplateImporter(),
        bundledRa: List<RetroArchCfgEntry> = emptyList(),
    ) = TemplateResolver(repo, paddleboat, bundledRa, tempFolder.root)

    @Test
    fun returns_existing_on_disk_template_when_match_rule_scores_above_zero() {
        val repo = makeRepo()
        val saved = DeviceTemplate(
            id = "stadia_user",
            displayName = "Stadia (user)",
            match = DeviceMatchRule(vendorId = 6353, productId = 37888),
            bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
            source = TemplateSource.USER_WIZARD,
            userEdited = true,
        )
        repo.save(saved)

        val resolver = makeResolver(repo)
        val resolved = resolver.resolve(device)

        assertEquals("stadia_user", resolved.template.id)
        assertTrue(resolved.persistent)
    }

    @Test
    fun on_score_tie_picks_most_recently_edited_template() {
        val repo = makeRepo()
        repo.save(
            DeviceTemplate(
                id = "older",
                displayName = "older",
                match = DeviceMatchRule(name = "Stadia Controller"),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = TemplateSource.PADDLEBOAT_DB,
            )
        )
        Thread.sleep(20)
        repo.save(
            DeviceTemplate(
                id = "newer",
                displayName = "newer",
                match = DeviceMatchRule(name = "Stadia Controller"),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = TemplateSource.PADDLEBOAT_DB,
            )
        )

        val resolved = makeResolver(repo).resolve(device)
        assertEquals("newer", resolved.template.id)
    }

    @Test
    fun no_match_uses_paddleboat_importer_and_persists_result() {
        val repo = makeRepo()
        val pb = object : PaddleboatTemplateImporter {
            override fun importFor(device: ConnectedDevice): DeviceTemplate =
                DeviceTemplate(
                    id = "pb_stadia",
                    displayName = "Stadia (Paddleboat)",
                    match = DeviceMatchRule(vendorId = 6353, productId = 37888),
                    bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                    source = TemplateSource.PADDLEBOAT_DB,
                )
        }
        val resolved = makeResolver(repo, paddleboat = pb).resolve(device)
        assertEquals("pb_stadia", resolved.template.id)
        assertTrue(resolved.persistent)
        assertNotNull(repo.findById("pb_stadia"))
    }

    @Test
    fun no_match_falls_through_paddleboat_to_ra_autoconfig_when_bundled_entry_matches() {
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
        assertEquals(TemplateSource.RETROARCH_AUTOCONFIG, resolved.template.source)
        assertTrue(resolved.persistent)
        assertNotNull(repo.findById(resolved.template.id))
    }

    @Test
    fun nothing_matches_yields_runtime_android_default_not_persisted() {
        val repo = makeRepo()
        val resolved = makeResolver(repo).resolve(device)
        assertEquals(TemplateSource.ANDROID_DEFAULT, resolved.template.source)
        assertFalse(resolved.persistent)
        assertEquals(0, repo.list().size)
    }

    @Test
    fun resolver_priority_is_disk_then_paddleboat_then_ra_then_default() {
        val repo = makeRepo()
        val saved = DeviceTemplate(
            id = "disk_wins",
            displayName = "Disk wins",
            match = DeviceMatchRule(vendorId = 6353, productId = 37888),
            bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
            source = TemplateSource.RETROARCH_AUTOCONFIG,
        )
        repo.save(saved)
        val pb = object : PaddleboatTemplateImporter {
            override fun importFor(device: ConnectedDevice) =
                DeviceTemplate(
                    id = "pb_loses",
                    displayName = "Paddleboat loses",
                    match = DeviceMatchRule(vendorId = 6353, productId = 37888),
                    bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                    source = TemplateSource.PADDLEBOAT_DB,
                )
        }
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )
        val resolved = makeResolver(repo, paddleboat = pb, bundledRa = ra).resolve(device)
        assertEquals("disk_wins", resolved.template.id)
    }
}
