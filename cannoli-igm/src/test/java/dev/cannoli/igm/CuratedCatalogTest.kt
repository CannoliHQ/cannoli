package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedCatalogTest {

    private val scaling = row("video", "curated_screen_scaling")
    private val hud = row("advanced", "curated_debug_hud")

    private fun row(category: String, key: String) =
        CuratedCatalog.categories.first { it.key == category }.rows.first { it.key == key }

    @Test
    fun `a row reports every key its presets touch`() {
        assertEquals(
            setOf("aspect_ratio_index", "video_scale_integer"),
            scaling.settingKeys,
        )
    }

    // A preset that leaves a key out would keep whatever the previous preset wrote, so switching
    // presets would land on a combination no preset describes.
    @Test
    fun `every preset of a row sets exactly the keys the row owns`() {
        for (cat in CuratedCatalog.categories) {
            for (r in cat.rows) {
                for (preset in r.presets) {
                    assertEquals(
                        "${r.key} preset ${preset.labelKey} must set every key the row owns",
                        r.settingKeys,
                        preset.values.keys,
                    )
                }
            }
        }
    }

    @Test
    fun `resolve returns the preset whose values all match`() {
        val first = scaling.presets.first()
        assertEquals(first, CuratedCatalog.resolve(scaling, first.values))
    }

    @Test
    fun `resolve ignores keys the row does not own`() {
        val first = scaling.presets.first()
        assertEquals(first, CuratedCatalog.resolve(scaling, first.values + ("video_smooth" to "true")))
    }

    @Test
    fun `resolve returns null when no preset matches`() {
        assertNull(CuratedCatalog.resolve(scaling, scaling.settingKeys.associateWith { "-999" }))
    }

    // A key absent from the map is one RetroArch does not expose on this build, so it cannot
    // disagree with anything. Treating it as a mismatch would make the row read Custom forever.
    @Test
    fun `resolve ignores a key the host does not report`() {
        val first = scaling.presets.first()
        assertEquals(first, CuratedCatalog.resolve(scaling, first.values - "video_scale_integer_overscale"))
    }

    @Test
    fun `resolve still rejects a key that is present and wrong`() {
        val first = scaling.presets.first()
        assertNull(CuratedCatalog.resolve(scaling, first.values + ("aspect_ratio_index" to "-999")))
    }

    @Test
    fun `discriminating keys are the ones presets disagree about`() {
        assertEquals(setOf("aspect_ratio_index", "video_scale_integer"), scaling.discriminatingKeys)
    }

    // RetroArch renders floats with %g, so a ratio of 2.0 arrives as "2". A preset written as "2.0"
    // must still match, or the row reads Custom for a value the user just set from this menu.
    @Test
    fun `resolve compares numbers numerically rather than as text`() {
        val ff = row("advanced", "curated_max_ff_speed")
        val preset = ff.presets.first()
        val key = ff.settingKeys.single()
        val asFloat = preset.values.getValue(key).toDouble()
        assertEquals(preset, CuratedCatalog.resolve(ff, mapOf(key to asFloat.toString())))
        assertEquals(preset, CuratedCatalog.resolve(ff, mapOf(key to asFloat.toInt().toString())))
    }

    @Test
    fun `resolve still compares non-numbers exactly`() {
        assertNull(CuratedCatalog.resolve(hud, hud.settingKeys.associateWith { "TRUE" }))
    }

    @Test
    fun `nextPreset advances from the matching preset and wraps`() {
        assertEquals(scaling.presets[1], CuratedCatalog.nextPreset(scaling, scaling.presets.first().values, 1))
        assertEquals(scaling.presets.first(), CuratedCatalog.nextPreset(scaling, scaling.presets.last().values, 1))
    }

    @Test
    fun `nextPreset goes backwards too`() {
        assertEquals(scaling.presets.last(), CuratedCatalog.nextPreset(scaling, scaling.presets.first().values, -1))
    }

    // Custom has no position in the list, so cycling off it must land somewhere defined rather than
    // on an arbitrary neighbour of an index that does not exist.
    @Test
    fun `nextPreset from Custom lands on the first preset in either direction`() {
        val bogus = scaling.settingKeys.associateWith { "-999" }
        assertEquals(scaling.presets.first(), CuratedCatalog.nextPreset(scaling, bogus, 1))
        assertEquals(scaling.presets.first(), CuratedCatalog.nextPreset(scaling, bogus, -1))
    }

    @Test
    fun `row keys are unique across the whole catalog`() {
        val keys = CuratedCatalog.categories.flatMap { it.rows }.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `category keys are unique`() {
        val keys = CuratedCatalog.categories.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `every row has at least two presets`() {
        for (cat in CuratedCatalog.categories) {
            for (r in cat.rows) {
                assertTrue("${r.key} needs something to cycle between", r.presets.size >= 2)
            }
        }
    }

    // Cannoli writes input_driver = "android" into every controller cfg it generates. A curated row
    // that touched either driver key would break every controller mapping in game.
    @Test
    fun `no curated row touches a driver key`() {
        val all = CuratedCatalog.categories.flatMap { it.rows }.flatMap { it.settingKeys }
        assertTrue(all.none { it == "input_driver" || it == "joypad_driver" || it == "menu_driver" })
    }
}
