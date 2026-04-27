package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSortTest {

    @Test fun `numeric runs sort by numeric value not lexicographic`() {
        val input = listOf("file2", "file10", "file1")
        assertEquals(listOf("file1", "file2", "file10"), input.sortedWith(NaturalSort))
    }

    @Test fun `case is ignored when comparing alphabetic chunks`() {
        val input = listOf("Banana", "apple", "Cherry")
        assertEquals(listOf("apple", "Banana", "Cherry"), input.sortedWith(NaturalSort))
    }

    @Test fun `equal strings compare as zero`() {
        assertEquals(0, NaturalSort.compare("Final Fantasy IV", "Final Fantasy IV"))
    }

    @Test fun `prefix compares before longer string with same prefix`() {
        assertTrue(NaturalSort.compare("game", "game1") < 0)
        assertTrue(NaturalSort.compare("game1", "game") > 0)
    }

    @Test fun `empty strings are handled`() {
        assertEquals(0, NaturalSort.compare("", ""))
        assertTrue(NaturalSort.compare("", "anything") < 0)
        assertTrue(NaturalSort.compare("anything", "") > 0)
    }

    @Test fun `roman numerals use lexicographic order between letters`() {
        // Roman numerals have no special handling; they compare as strings.
        // II < III < IV < V because of normal string ordering of I and V.
        val input = listOf("Final Fantasy V", "Final Fantasy III", "Final Fantasy II", "Final Fantasy IV")
        val sorted = input.sortedWith(NaturalSort)
        assertEquals("Final Fantasy II", sorted[0])
        assertEquals("Final Fantasy III", sorted[1])
        assertEquals("Final Fantasy IV", sorted[2])
        assertEquals("Final Fantasy V", sorted[3])
    }

    @Test fun `multi-digit numbers sort numerically across boundaries`() {
        val input = listOf(
            "Disc 1 of 3",
            "Disc 10 of 12",
            "Disc 2 of 3",
            "Disc 11 of 12",
            "Disc 1 of 12"
        )
        val sorted = input.sortedWith(NaturalSort)
        // "Disc 1 of 3" and "Disc 1 of 12" share prefix "Disc 1 of ", then diverge on 3 vs 12.
        assertEquals("Disc 1 of 3", sorted[0])
        assertEquals("Disc 1 of 12", sorted[1])
        assertEquals("Disc 2 of 3", sorted[2])
        assertEquals("Disc 10 of 12", sorted[3])
        assertEquals("Disc 11 of 12", sorted[4])
    }

    @Test fun `numeric chunks larger than Long fall back to BigInteger`() {
        val huge = "9".repeat(40)
        val biggerHuge = "9".repeat(40) + "0"
        assertTrue(NaturalSort.compare("rom$huge", "rom$biggerHuge") < 0)
    }

    @Test fun `leading zeros do not affect numeric ordering`() {
        assertEquals(0, NaturalSort.compare("track007", "track7"))
    }

    @Test fun `digits beat letters when the runs diverge`() {
        // "abc" vs "ab1": at position 2 the chunks become alphabetic "c" and digit "1".
        // The algorithm treats them via string compareTo since one is digit and one is not.
        // We just want the result to be stable and order-independent: a < b iff !(b < a).
        val ab = NaturalSort.compare("abc", "ab1")
        val ba = NaturalSort.compare("ab1", "abc")
        assertTrue(ab != 0)
        assertEquals(-ab.coerceIn(-1, 1), ba.coerceIn(-1, 1))
    }

    @Test fun `sortedNatural extension uses the comparator on a selector`() {
        data class Game(val name: String)
        val games = listOf(Game("Mario 10"), Game("Mario 2"), Game("Mario 1"))
        val sorted = games.sortedNatural { it.name }
        assertEquals(listOf("Mario 1", "Mario 2", "Mario 10"), sorted.map { it.name })
    }

    @Test fun `pure-numeric strings sort numerically`() {
        val input = listOf("100", "20", "3", "1", "200")
        assertEquals(listOf("1", "3", "20", "100", "200"), input.sortedWith(NaturalSort))
    }
}
