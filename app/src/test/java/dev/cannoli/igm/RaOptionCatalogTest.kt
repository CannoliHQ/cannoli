package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaOptionCatalogTest {

    @Test
    fun categoriesAreNonEmptyWithUniqueKeys() {
        assertTrue(RaOptionCatalog.categories.isNotEmpty())
        val catKeys = RaOptionCatalog.categories.map { it.key }
        assertEquals(catKeys.size, catKeys.distinct().size)
        RaOptionCatalog.categories.forEach { cat ->
            assertTrue(cat.settingKeys.isNotEmpty())
            assertEquals(cat.settingKeys.size, cat.settingKeys.distinct().size)
        }
    }

    @Test
    fun noSettingKeyAppearsInTwoCategories() {
        val all = RaOptionCatalog.categories.flatMap { it.settingKeys }
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun categoriesMatchExpectedTaxonomy() {
        val keys = RaOptionCatalog.categories.map { it.key }
        assertEquals(
            listOf("video", "audio", "latency", "speed", "input", "savestates", "osd"),
            keys,
        )
    }

    @Test
    fun hardcoreToggleLivesInSaveStates() {
        val savestates = RaOptionCatalog.categories.first { it.key == "savestates" }
        assertTrue(savestates.settingKeys.contains("cheevos_hardcore_mode_enable"))
    }
}
