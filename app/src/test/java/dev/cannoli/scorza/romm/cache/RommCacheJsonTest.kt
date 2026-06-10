package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommFile
import org.junit.Assert.assertEquals
import org.junit.Test

class RommCacheJsonTest {

    @Test fun `files round-trip including hashes`() {
        val files = listOf(
            RommFile("disc1.bin", 100L, crc = "abc", md5 = "def", sha1 = null),
            RommFile("disc2.bin", 200L, crc = null, md5 = null, sha1 = "999"),
        )
        val decoded = RommCacheJson.decodeFiles(RommCacheJson.encodeFiles(files))
        assertEquals(files, decoded)
    }

    @Test fun `empty files round-trip`() {
        assertEquals(emptyList<RommFile>(), RommCacheJson.decodeFiles(RommCacheJson.encodeFiles(emptyList())))
    }

    @Test fun `strings round-trip`() {
        val regions = listOf("USA", "Europe")
        assertEquals(regions, RommCacheJson.decodeStrings(RommCacheJson.encodeStrings(regions)))
    }
}
