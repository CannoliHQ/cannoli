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
            listOf("video", "audio", "latency", "speed", "osd"),
            keys,
        )
    }

    @Test
    fun categorySizesMatchDesign() {
        fun size(key: String) = RaOptionCatalog.categories.first { it.key == key }.settingKeys.size
        assertEquals(11, size("video"))
        assertEquals(9, size("audio"))
        assertEquals(10, size("latency"))
        assertEquals(7, size("speed"))
        assertEquals(18, size("osd"))
    }
}
