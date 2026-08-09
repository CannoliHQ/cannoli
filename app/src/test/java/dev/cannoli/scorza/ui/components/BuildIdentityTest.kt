package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class BuildIdentityTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-08-08 18:41 UTC
    private val stamp = 1786214460000L

    private fun lines(debug: Boolean, dirty: Boolean = false, zone: TimeZone = utc) =
        buildIdentityLines(
            debug = debug,
            versionName = "1.0.0",
            hash = "abc1234",
            dirty = dirty,
            buildTimeMillis = stamp,
            debugLabel = "DEBUG",
            zone = zone,
        )

    @Test fun release_keeps_the_version_date_and_hash_on_one_line() {
        val l = lines(debug = false)
        assertEquals("v1.0.0  •  2026-08-08  •  abc1234", l.version)
        assertNull(l.detail)
    }

    @Test fun release_never_shows_the_dirty_marker() {
        assertEquals(lines(debug = false).version, lines(debug = false, dirty = true).version)
    }

    @Test fun debug_replaces_the_version_with_the_debug_label() {
        assertEquals("DEBUG", lines(debug = true).version)
    }

    @Test fun debug_details_carry_the_hash_and_a_datetime() {
        assertEquals("abc1234  •  2026-08-08 18:41", lines(debug = true).detail)
    }

    @Test fun a_dirty_tree_suffixes_the_hash() {
        assertEquals("abc1234-dirty  •  2026-08-08 18:41", lines(debug = true, dirty = true).detail)
    }

    @Test fun the_datetime_is_rendered_in_the_given_zone() {
        val ny = lines(debug = true, zone = TimeZone.getTimeZone("America/New_York")).detail
        assertEquals("abc1234  •  2026-08-08 14:41", ny)
    }
}
