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
        bundledRa: List<RetroArchCfgEntry> = emptyList(),
        menuConvention: () -> dev.cannoli.ui.ConfirmButton = { dev.cannoli.ui.ConfirmButton.EAST },
    ) = TemplateResolver(repo, bundledRa, menuConvention, tempFolder.root)

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

        val resolved = makeResolver(repo).resolve(device)

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
                source = TemplateSource.RETROARCH_AUTOCONFIG,
            )
        )
        Thread.sleep(20)
        repo.save(
            DeviceTemplate(
                id = "newer",
                displayName = "newer",
                match = DeviceMatchRule(name = "Stadia Controller"),
                bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                source = TemplateSource.RETROARCH_AUTOCONFIG,
            )
        )

        val resolved = makeResolver(repo).resolve(device)
        assertEquals("newer", resolved.template.id)
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
        assertEquals(TemplateSource.RETROARCH_AUTOCONFIG, resolved.template.source)
        assertFalse(resolved.persistent)
        assertEquals(0, repo.list().size)
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
    fun resolver_priority_is_disk_then_ra_then_default() {
        val repo = makeRepo()
        val saved = DeviceTemplate(
            id = "disk_wins",
            displayName = "Disk wins",
            match = DeviceMatchRule(vendorId = 6353, productId = 37888),
            bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
            source = TemplateSource.RETROARCH_AUTOCONFIG,
        )
        repo.save(saved)
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )
        val resolved = makeResolver(repo, bundledRa = ra).resolve(device)
        assertEquals("disk_wins", resolved.template.id)
    }

    @Test
    fun ra_imported_template_menu_confirm_follows_global_convention() {
        val repo = makeRepo()
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            )
        )

        val resolvedEast = makeResolver(repo, bundledRa = ra, menuConvention = { dev.cannoli.ui.ConfirmButton.EAST }).resolve(device)
        org.junit.Assert.assertEquals(CanonicalButton.BTN_EAST, resolvedEast.template.menuConfirm)
        org.junit.Assert.assertEquals(CanonicalButton.BTN_SOUTH, resolvedEast.template.menuBack)

        val resolvedSouth = makeResolver(repo, bundledRa = ra, menuConvention = { dev.cannoli.ui.ConfirmButton.SOUTH }).resolve(device)
        org.junit.Assert.assertEquals(CanonicalButton.BTN_SOUTH, resolvedSouth.template.menuConfirm)
        org.junit.Assert.assertEquals(CanonicalButton.BTN_EAST, resolvedSouth.template.menuBack)
    }
}
