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
        // Absent on an AYN Thor, 2026-08-22, checked by walking every Video screen in game. These
        // three are kept because the reason is not settled: RetroArch guards video_filter_enable on
        // HAVE_VIDEO_FILTER and the portrait bias pair on RARCH_MOBILE, which Android is, so they
        // ought to register and did not. Keys that RetroArch can NEVER register on Android were
        // deleted from the catalog instead of recorded here.
        "video_filter_enable",
        "video_viewport_bias_portrait_x",
        "video_viewport_bias_portrait_y",
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

    // Sizes are asserted per screen rather than per category, so a key silently moving between a
    // category and one of its subcategories fails rather than cancelling out.
    @Test
    fun categorySizesMatchDesign() {
        fun cat(key: String) = RaOptionCatalog.categories.first { it.key == key }
        fun size(key: String) = cat(key).settingKeys.size
        fun subSize(key: String, sub: String) =
            cat(key).subcategories.first { it.key == sub }.settingKeys.size

        assertEquals(3, size("video"))
        assertEquals(7, subSize("video", "output"))
        assertEquals(11, subSize("video", "scaling"))
        assertEquals(9, subSize("video", "synchronization"))
        assertEquals(5, subSize("video", "hdr"))

        assertEquals(8, size("audio"))
        assertEquals(4, subSize("audio", "output"))
        assertEquals(3, subSize("audio", "synchronization"))
        assertEquals(10, size("latency"))
        assertEquals(7, size("speed"))
        assertEquals(18, size("osd"))
    }

    // RetroArch itself lists several synchronization keys under both Video and Latency. Cannoli
    // gives a key one home, and these keep the one they already had, so expanding Video must not
    // quietly move them.
    @Test
    fun theSharedSynchronisationKeysStayUnderLatency() {
        val latency = RaOptionCatalog.categories.first { it.key == "latency" }.settingKeys
        for (key in listOf(
            "video_frame_delay", "video_frame_delay_auto",
            "video_hard_sync", "video_hard_sync_frames", "video_swap_interval",
        )) {
            assertTrue("$key belongs to Latency in Cannoli", latency.contains(key))
        }
        val speed = RaOptionCatalog.categories.first { it.key == "speed" }.settingKeys
        assertTrue(speed.contains("vrr_runloop_enable"))
    }

    // RetroArch's Drivers menu is a mixed bag, so it is not exposed as a category. The two worth
    // having are promoted into the categories they belong to.
    @Test
    fun videoAndAudioDriversArePromoted() {
        fun keys(key: String) = RaOptionCatalog.categories.first { it.key == key }
            .let { it.settingKeys + it.subcategories.flatMap { s -> s.settingKeys } }
        assertTrue(keys("video").contains("video_driver"))
        // RetroArch lists the audio driver under Audio > Output, so it sits there rather than at
        // the top level like the video one.
        assertTrue(keys("audio").contains("audio_driver"))
    }

    // Cannoli writes input_driver = "android" into every controller cfg it generates. Exposing
    // either input driver would let a user make no cfg match any pad, killing every mapping in
    // game. menu_driver is pointless because Cannoli replaces RetroArch's menu.
    @Test
    fun theDangerousAndPointlessDriversAreNeverExposed() {
        val all = RaOptionCatalog.categories
            .flatMap { it.settingKeys + it.subcategories.flatMap { s -> s.settingKeys } }.toSet()
        for (key in listOf("input_driver", "joypad_driver", "menu_driver")) {
            assertFalse("$key must never be exposed", all.contains(key))
        }
    }

    @Test
    fun driversIrrelevantOnAndroidAreNotExposed() {
        val all = RaOptionCatalog.categories
            .flatMap { it.settingKeys + it.subcategories.flatMap { s -> s.settingKeys } }.toSet()
        for (key in listOf(
            "microphone_driver", "record_driver", "midi_driver",
            "bluetooth_driver", "wifi_driver", "camera_driver", "location_driver",
        )) {
            assertFalse("$key is meaningless on this platform", all.contains(key))
        }
    }
}
