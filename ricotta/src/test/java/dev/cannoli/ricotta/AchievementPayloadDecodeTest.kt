package dev.cannoli.ricotta

import dev.cannoli.igm.AchievementInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire format ricotta_sb_escaped produces for the achievement snapshot. Every literal
 * below is the exact output of that C function, so changing the escaping means consciously
 * rewriting these bytes.
 */
class AchievementPayloadDecodeTest {

    @Test
    fun `decodes a plain row`() {
        assertEquals(
            listOf(
                AchievementInfo(
                    id = 12,
                    title = "First Blood",
                    description = "Beat the first stage",
                    points = 5,
                    unlocked = true,
                    state = 3,
                    unlockTime = 1700000000L,
                ),
            ),
            EmbeddedRetroArchBridge.decodeAchievements("12|First Blood|Beat the first stage|5|1|3|1700000000\n"),
        )
    }

    @Test
    fun `an escaped pipe stays inside its field`() {
        assertEquals(
            listOf(
                AchievementInfo(
                    id = 34,
                    title = "Pipe | in title",
                    description = "Score 10|000 points",
                    points = 25,
                    unlocked = false,
                    state = 1,
                    unlockTime = 0L,
                ),
            ),
            EmbeddedRetroArchBridge.decodeAchievements(
                "34|Pipe \\| in title|Score 10\\|000 points|25|0|1|0\n",
            ),
        )
    }

    @Test
    fun `an escaped newline stays inside its field`() {
        assertEquals(
            listOf(
                AchievementInfo(
                    id = 56,
                    title = "Two\nlines",
                    description = "Also\ntwo",
                    points = 10,
                    unlocked = true,
                    state = 3,
                    unlockTime = 42L,
                ),
            ),
            EmbeddedRetroArchBridge.decodeAchievements("56|Two\\nlines|Also\\ntwo|10|1|3|42\n"),
        )
    }

    @Test
    fun `an escaped backslash stays a backslash`() {
        assertEquals(
            listOf(
                AchievementInfo(
                    id = 78,
                    title = "Back\\slash",
                    description = "Literal\\nbackslash-n",
                    points = 0,
                    unlocked = false,
                    state = 0,
                    unlockTime = 0L,
                ),
            ),
            EmbeddedRetroArchBridge.decodeAchievements(
                "78|Back\\\\slash|Literal\\\\nbackslash-n|0|0|0|0\n",
            ),
        )
    }

    @Test
    fun `a malformed line drops without taking the rows around it`() {
        assertEquals(
            listOf(
                AchievementInfo(1, "A", "AA", 5, true, 3, 1L),
                AchievementInfo(3, "C", "CC", 5, false, 1, 0L),
            ),
            EmbeddedRetroArchBridge.decodeAchievements(
                "1|A|AA|5|1|3|1\nbroken\nnope|B|BB|5|0|1|0\n3|C|CC|5|0|1|0\n",
            ),
        )
    }

    @Test
    fun `an empty payload decodes to nothing`() {
        assertEquals(emptyList<AchievementInfo>(), EmbeddedRetroArchBridge.decodeAchievements(""))
    }
}
