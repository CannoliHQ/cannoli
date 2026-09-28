package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `files round-trip id and subDir`() {
        val files = listOf(
            RommFile("base.nsp", 10, null, null, null, id = 41, subDir = ""),
            RommFile("upd.nsp", 5, "c", "m", "s", id = 42, subDir = "update"),
        )
        val decoded = RommCacheJson.decodeFiles(RommCacheJson.encodeFiles(files))
        assertEquals(files, decoded)
    }

    @Test fun `files round-trip category and top-level`() {
        val files = listOf(
            RommFile("Game (USA).nes", 10, null, null, null, id = 1, category = "game", isTopLevel = true),
            RommFile("Game Hack.nes", 11, null, null, null, id = 2, subDir = "hacks", category = "hack"),
        )
        assertEquals(files, RommCacheJson.decodeFiles(RommCacheJson.encodeFiles(files)))
    }

    @Test fun `files cached before category and top-level decode with neither`() {
        val legacy = """[{"name":"base.nsp","size":10,"id":41,"dir":""}]"""
        val file = RommCacheJson.decodeFiles(legacy).single()
        assertEquals(RommFile("base.nsp", 10, null, null, null, id = 41), file)
        assertNull(file.category)
        assertFalse(file.isTopLevel)
    }

    @Test fun `strings round-trip`() {
        val regions = listOf("USA", "Europe")
        assertEquals(regions, RommCacheJson.decodeStrings(RommCacheJson.encodeStrings(regions)))
    }
}
