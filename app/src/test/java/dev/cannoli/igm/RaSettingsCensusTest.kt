package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaSettingsCensusTest {

    private data class Census(val key: String, val type: String, val screen: String, val cppGuard: String)

    private val census: Map<String, Census> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("ra-settings-census.tsv")
            ?: error("ra-settings-census.tsv missing. Regenerate with scripts/ra-settings-census.py")
        stream.bufferedReader().readLines().drop(1)
            .filter { it.isNotBlank() }
            .map { it.split("\t") }
            .associate { it[0] to Census(it[0], it[1], it[2], it.getOrElse(3) { "" }) }
    }

    private fun catalogKeys(): List<Pair<String, String>> =
        CuratedCatalog.categories.flatMap { cat ->
            cat.rows.flatMap { row -> row.settingKeys.map { "${cat.key}/${row.key}" to it } }
        }

    @Test
    fun `the census is present and plausible`() {
        assertTrue("census looks truncated: ${census.size} rows", census.size > 500)
        assertEquals("aspect_ratio_index", census["aspect_ratio_index"]?.key)
    }

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
