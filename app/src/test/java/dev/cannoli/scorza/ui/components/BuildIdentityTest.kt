package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class BuildIdentityTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-08-08 18:41 UTC
    private val stamp = 1786214460000L

    // 2026-08-08 02:30 UTC, which is still 2026-08-07 in every zone behind UTC.
    private val nearMidnightUtc = 1786156200000L

    private fun lines(
        debug: Boolean,
        dirty: Boolean = false,
        millis: Long = stamp,
    ) = buildIdentityLines(
        debug = debug,
        versionName = "1.0.0",
        hash = "abc1234",
        dirty = dirty,
        buildTimeMillis = millis,
        debugLabel = "DEBUG",
    )

    private fun linesIn(zone: String, debug: Boolean, dirty: Boolean = false, millis: Long = stamp) =
        buildIdentityLines(
            debug = debug,
            versionName = "1.0.0",
            hash = "abc1234",
            dirty = dirty,
            buildTimeMillis = millis,
            debugLabel = "DEBUG",
            deviceZone = TimeZone.getTimeZone(zone),
        )

    @Test fun release_keeps_the_version_date_and_hash_on_one_line() {
        val l = lines(debug = false)
        assertEquals("v1.0.0  •  2026-08-08  •  abc1234", l.version)
        assertNull(l.detail)
    }

    // The date names when the build was cut. Derived in the viewer's zone instead, a build cut just
    // after midnight UTC would show the previous day to everyone west of it.
    @Test fun the_release_date_is_utc_whatever_zone_the_device_is_in() {
        val expected = "v1.0.0  •  2026-08-08  •  abc1234"
        assertEquals(expected, lines(debug = false, millis = nearMidnightUtc).version)
        assertEquals(expected, linesIn("Pacific/Midway", debug = false, millis = nearMidnightUtc).version)
        assertEquals(expected, linesIn("Pacific/Kiritimati", debug = false, millis = nearMidnightUtc).version)
        assertEquals(expected, linesIn("America/New_York", debug = false, millis = nearMidnightUtc).version)
    }

    @Test fun release_never_shows_the_dirty_marker() {
        assertEquals(lines(debug = false).version, lines(debug = false, dirty = true).version)
    }

    @Test fun debug_replaces_the_version_with_the_debug_label() {
        assertEquals("DEBUG", lines(debug = true).version)
    }

    @Test fun debug_details_carry_the_hash_and_a_datetime() {
        assertEquals("abc1234  •  2026-08-08 18:41", linesIn("UTC", debug = true).detail)
    }

    @Test fun a_dirty_tree_suffixes_the_hash() {
        assertEquals("abc1234-dirty  •  2026-08-08 18:41", linesIn("UTC", debug = true, dirty = true).detail)
    }

    @Test fun the_debug_datetime_stays_in_the_device_zone() {
        assertEquals("abc1234  •  2026-08-08 14:41", linesIn("America/New_York", debug = true).detail)
        assertEquals("abc1234  •  2026-08-08 18:41", linesIn("UTC", debug = true).detail)
    }
}
