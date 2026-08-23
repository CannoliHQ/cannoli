package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the catalog against a census of what RetroArch actually registers, generated from the
 * vendored source by scripts/ra-settings-census.py.
 *
 * Cannoli reaches a setting by config key through ricotta_ra_find -> menu_setting_find, and a key
 * with no menu registration is dropped silently by mapNotNull. So a wrong key is not an error, it
 * is a row that never appears, which is how video_scale_integer_overscale, run_ahead_enabled and
 * six others sat in the catalog doing nothing. This turns that into a failing test.
 */
class RaSettingsCensusTest {

    private data class Census(val key: String, val type: String, val screen: String, val cppGuard: String)

    private val census: Map<String, Census> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("ra-settings-census.tsv")
            ?: error("ra-settings-census.tsv missing. Regenerate with scripts/ra-settings-census.py")
        stream.bufferedReader().readLines().drop(1)
            .filter { it.isNotBlank() }
            .map { it.split("\t") }
            .associate { it[0] to Census(it[0], it[2], it[3], it.getOrElse(4) { "" }) }
    }

    // All Settings names no keys any more: RetroArch supplies its rows, so a key that stops existing
    // simply stops appearing. Curated still names them, and a curated row whose keys RetroArch does
    // not register is a row that silently never shows, which is what this guards.
    private fun catalogKeys(): List<Pair<String, String>> =
        CuratedCatalog.categories.flatMap { cat ->
            cat.rows.flatMap { row -> row.settingKeys.map { "${cat.key}/${row.key}" to it } }
        }

    @Test
    fun `the census is present and plausible`() {
        assertTrue("census looks truncated: ${census.size} rows", census.size > 500)
        assertEquals("aspect_ratio_index", census["aspect_ratio_index"]?.key)
    }

    // The one that would have caught every bad key added this week.
    @Test
    fun `every catalog key is a setting RetroArch registers`() {
        val unknown = catalogKeys()
            .filterNot { (_, key) -> key.startsWith("cannoli_") }
            .filterNot { (_, key) -> census.containsKey(key) }
        assertTrue(
            "these catalog keys are not registered by RetroArch, so their rows can never appear:\n" +
                unknown.joinToString("\n") { (screen, key) -> "  $screen -> $key" },
            unknown.isEmpty(),
        )
    }

    // A path or free string renders as STRING_RO, which RaValueCycler cannot cycle, so the row
    // shows a value and does nothing. audio_dsp_plugin and video_filter both shipped that way.
    @Test
    fun `no catalog key is a setting nothing can change`() {
        val inert = catalogKeys()
            .filterNot { (_, key) -> key.startsWith("cannoli_") }
            .filter { (_, key) -> census[key]?.type in setOf("PATH", "DIR", "STRING") }
        assertTrue(
            "these render as read-only rows with no picker behind them:\n" +
                inert.joinToString("\n") { (screen, key) -> "  $screen -> $key (${census[key]?.type})" },
            inert.isEmpty(),
        )
    }

    // A setting RetroArch compiles out on Android can never appear, whatever the catalog says.
    @Test
    fun `no catalog key is compiled out on this platform`() {
        val impossible = catalogKeys()
            .filterNot { (_, key) -> key.startsWith("cannoli_") }
            .mapNotNull { (screen, key) ->
                val guard = census[key]?.cppGuard ?: return@mapNotNull null
                val dead = listOf("GEKKO", "HAVE_ODROIDGO2", "TARGET_OS_IOS", "_WIN32", "__WINRT__")
                    .firstOrNull { guard.contains(it) }
                    ?: if (guard.contains("!defined(RARCH_MOBILE)")) "!defined(RARCH_MOBILE)" else null
                dead?.let { Triple(screen, key, it) }
            }
        assertTrue(
            "these are guarded out of an Android build:\n" +
                impossible.joinToString("\n") { (screen, key, g) -> "  $screen -> $key ($g)" },
            impossible.isEmpty(),
        )
    }
}
