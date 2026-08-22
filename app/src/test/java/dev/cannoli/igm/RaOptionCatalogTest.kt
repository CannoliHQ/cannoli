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
        val all = RaOptionCatalog.categories
            .flatMap { it.settingKeys + it.subcategories.flatMap { s -> s.settingKeys } }
        assertEquals(all.size, all.distinct().size)
    }

    // ricotta_ra_find returns null for a setting RetroArch never registered, and loadKeys drops it
    // through mapNotNull, so a wrong or unregistered key produces a row that silently never appears
    // rather than an error. This set is the record of keys confirmed on device not to resolve.
    // Adding to it is a decision to be made after checking the device, not a way to quiet a test.
    private val knownUnresolved = setOf(
        // In the catalog since before the curated work and confirmed absent on an AYN Thor:
        // requiring it deleted the whole curated Screen Scaling row until the rule changed to
        // discriminating keys only.
        "video_scale_integer_overscale",
    )

    @Test
    fun everyKnownUnresolvedKeyIsStillInTheCatalog() {
        val all = RaOptionCatalog.categories
            .flatMap { it.settingKeys + it.subcategories.flatMap { s -> s.settingKeys } }
            .toSet()
        for (key in knownUnresolved) {
            assertTrue(
                "$key is recorded as not resolving but is no longer in the catalog, so the record " +
                    "is stale and should be removed with it",
                all.contains(key),
            )
        }
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
