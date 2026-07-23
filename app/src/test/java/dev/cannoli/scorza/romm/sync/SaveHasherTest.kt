package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveHasherTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        File(tmp.root, name).apply { writeBytes(bytes) }

    @Test fun md5_of_empty_matches_romm() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", SaveHasher.md5Hex(ByteArray(0)))
    }

    @Test fun md5_of_hello_matches_romm() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", SaveHasher.md5Hex("hello".toByteArray()))
    }

    @Test fun hashFile_matches_romm_compute_file_hash() {
        val f = file("game.srm", byteArrayOf('S'.code.toByte(),'R'.code.toByte(),'A'.code.toByte(),'M'.code.toByte(),'D'.code.toByte(),'A'.code.toByte(),'T'.code.toByte(),'A'.code.toByte(),0,1))
        assertEquals("affa4ec5ace8fe0a12bbca5a7ebf48a5", SaveHasher.hashFile(f))
    }

    @Test fun hashBundle_matches_romm_compute_zip_hash() {
        val srm = file("game.srm", byteArrayOf('S'.code.toByte(),'R'.code.toByte(),'A'.code.toByte(),'M'.code.toByte(),'D'.code.toByte(),'A'.code.toByte(),'T'.code.toByte(),'A'.code.toByte(),0,1))
        val rtc = file("game.rtc", "RTCDATA".toByteArray())
        assertEquals("911c7ddbd6cf0291bc6a7c89e8dd05c5", SaveHasher.hashBundle(mapOf("game.srm" to srm, "game.rtc" to rtc)))
    }

    @Test fun hashBundle_sorts_entries_by_name() {
        val a = file("a.mcr", "one".toByteArray())
        val b = file("b.mcr", "two".toByteArray())
        // Insertion order b,a must not change the result; RomM sorts namelist().
        assertEquals("c065716f579488420c1c940ccafabbb3", SaveHasher.hashBundle(mapOf("b.mcr" to b, "a.mcr" to a)))
    }
}
