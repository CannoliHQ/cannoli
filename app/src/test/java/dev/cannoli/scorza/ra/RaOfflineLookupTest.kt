package dev.cannoli.scorza.ra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RaOfflineLookupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seed(): File {
        val dir = tmp.root
        File(dir, "login2.json").writeText("login")
        File(dir, "55").apply { mkdirs() }
        File(dir, "55/achievementsets.json").writeText("sets")
        File(dir, "55/startsession.json").writeText("session")
        File(dir, "55/hash").writeText("abcdef0123456789")
        return dir
    }

    @Test fun login2_servedByRequestType() {
        assertEquals("login", RaOfflineLookup.bodyFor(seed(), "r=login2&u=bob&t=tok"))
    }

    @Test fun achievementSets_servedByGameIdParam() {
        assertEquals("sets", RaOfflineLookup.bodyFor(seed(), "r=achievementsets&u=bob&t=tok&g=55"))
    }

    @Test fun achievementSets_servedByHashLookup() {
        assertEquals("sets", RaOfflineLookup.bodyFor(seed(), "r=achievementsets&u=bob&t=tok&m=abcdef0123456789"))
    }

    @Test fun startSession_servedByGameIdParam() {
        assertEquals("session", RaOfflineLookup.bodyFor(seed(), "r=startsession&u=bob&t=tok&g=55&l=12.3.0&h=0&m=abcdef0123456789"))
    }

    @Test fun achievementSets_unknownHash_returnsNull() {
        assertNull(RaOfflineLookup.bodyFor(seed(), "r=achievementsets&u=bob&t=tok&m=deadbeef"))
    }

    @Test fun unknownRequest_returnsNull() {
        assertNull(RaOfflineLookup.bodyFor(seed(), "r=ping&g=55"))
    }

    @Test fun missingDir_returnsNull() {
        assertNull(RaOfflineLookup.bodyFor(null, "r=login2"))
    }
}
