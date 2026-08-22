package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(12, size("video"))
        assertEquals(10, size("audio"))
        assertEquals(10, size("latency"))
        assertEquals(7, size("speed"))
        assertEquals(18, size("osd"))
    }

    // RetroArch's Drivers menu is a mixed bag, so it is not exposed as a category. The two worth
    // having are promoted into the categories they belong to.
    @Test
    fun videoAndAudioDriversArePromoted() {
        fun keys(key: String) = RaOptionCatalog.categories.first { it.key == key }.settingKeys
        assertTrue(keys("video").contains("video_driver"))
        assertTrue(keys("audio").contains("audio_driver"))
    }

    // Cannoli writes input_driver = "android" into every controller cfg it generates. Exposing
    // either input driver would let a user make no cfg match any pad, killing every mapping in
    // game. menu_driver is pointless because Cannoli replaces RetroArch's menu.
    @Test
    fun theDangerousAndPointlessDriversAreNeverExposed() {
        val all = RaOptionCatalog.categories.flatMap { it.settingKeys }.toSet()
        for (key in listOf("input_driver", "joypad_driver", "menu_driver")) {
            assertFalse("$key must never be exposed", all.contains(key))
        }
    }

    @Test
    fun driversIrrelevantOnAndroidAreNotExposed() {
        val all = RaOptionCatalog.categories.flatMap { it.settingKeys }.toSet()
        for (key in listOf(
            "microphone_driver", "record_driver", "midi_driver",
            "bluetooth_driver", "wifi_driver", "camera_driver", "location_driver",
        )) {
            assertFalse("$key is meaningless on this platform", all.contains(key))
        }
    }
}
