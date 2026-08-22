package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNestHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raSetSetting(key: String, value: String) = true
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {}
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}
    override fun getLocalToggle(key: String, default: Boolean) = default
    override fun setLocalToggle(key: String, value: Boolean) {}

    fun seed(vararg keys: String) {
        for (k in keys) settings[k] = RaSetting(k, k, RaSettingType.BOOL, "false", rawValue = "false")
    }

    fun seedEveryCatalogKey() {
        for (cat in RaOptionCatalog.categories) {
            seed(*cat.settingKeys.toTypedArray())
            for (sub in cat.subcategories) seed(*sub.settingKeys.toTypedArray())
        }
    }
}

class RaOptionCatalogNestingTest {

    private fun provider(h: FakeNestHost) = RaIgmSettingsProvider(
        host = h, strings = RaOptionStrings(), debugBuild = false, curated = false, onOpenNativeMenu = {},
    )

    // A synthetic category so this test does not depend on which real category happens to be nested
    // at any point during the expansion.
    private val nested = RaOptionCatalog.Category(
        key = "video",
        settingKeys = listOf("video_smooth"),
        subcategories = listOf(
            RaOptionCatalog.Category("scaling", listOf("aspect_ratio_index", "video_scale_integer")),
            RaOptionCatalog.Category("output", listOf("video_threaded")),
        ),
    )

    @Test
    fun `a category lists its own settings before its subcategories`() {
        val h = FakeNestHost().apply { seed("video_smooth", "aspect_ratio_index", "video_scale_integer", "video_threaded") }
        val items = provider(h).categoryScreenFor(nested).items
        val lastChoice = items.indexOfLast { it is GenericIgmSettingsItem.Choice }
        val firstSub = items.indexOfFirst { it is GenericIgmSettingsItem.Category }
        assertTrue("settings must come before subcategory rows", lastChoice < firstSub)
    }

    @Test
    fun `subcategory rows appear in catalog order`() {
        val h = FakeNestHost().apply { seed("video_smooth", "aspect_ratio_index", "video_scale_integer", "video_threaded") }
        val subs = provider(h).categoryScreenFor(nested).items
            .filterIsInstance<GenericIgmSettingsItem.Category>().map { it.key }
        assertEquals(listOf("scaling", "output"), subs)
    }

    // loadCategory drops keys that do not resolve, so a subcategory whose keys are all absent would
    // otherwise be a row leading to an empty screen.
    @Test
    fun `a subcategory with nothing resolvable is absent rather than a dead end`() {
        val h = FakeNestHost().apply { seed("video_smooth", "video_threaded") }
        val subs = provider(h).categoryScreenFor(nested).items
            .filterIsInstance<GenericIgmSettingsItem.Category>().map { it.key }
        assertEquals(listOf("output"), subs)
    }

    @Test
    fun `a subcategory screen shows only its own settings`() {
        val h = FakeNestHost().apply { seed("video_smooth", "aspect_ratio_index", "video_scale_integer", "video_threaded") }
        val keys = provider(h).subcategoryScreenFor(nested, "scaling").items.map { it.key }
        assertEquals(listOf("aspect_ratio_index", "video_scale_integer"), keys)
    }

    // The parent and the subcategory are different screens over the same provider, so they must not
    // share a cache slot or entering one would show the other's rows.
    @Test
    fun `moving between a category and its subcategory reloads the right rows`() {
        val h = FakeNestHost().apply { seed("video_smooth", "aspect_ratio_index", "video_scale_integer", "video_threaded") }
        val p = provider(h)
        assertEquals(listOf("video_smooth"), p.categoryScreenFor(nested).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().map { it.key })
        assertEquals(listOf("aspect_ratio_index", "video_scale_integer"),
            p.subcategoryScreenFor(nested, "scaling").items.map { it.key })
        assertEquals(listOf("video_smooth"), p.categoryScreenFor(nested).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().map { it.key })
    }

    @Test
    fun `nesting is one level, so no subcategory declares its own`() {
        for (cat in RaOptionCatalog.categories) {
            for (sub in cat.subcategories) {
                assertTrue(
                    "${cat.key}/${sub.key} declares subcategories, which are never rendered",
                    sub.subcategories.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `no setting key appears in two places in the catalog`() {
        val all = RaOptionCatalog.categories
            .flatMap { it.settingKeys + it.subcategories.flatMap { s -> s.settingKeys } }
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun `subcategory paths are unique`() {
        val paths = RaOptionCatalog.categories.flatMap { c -> c.subcategories.map { "${c.key}/${it.key}" } }
        assertEquals(paths.size, paths.distinct().size)
    }
}
