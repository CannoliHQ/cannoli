package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementSortTest {
    private fun ach(id: Int, unlockTime: Long) =
        AchievementInfo(id, "title-$id", "desc", 5, unlocked = true, unlockTime = unlockTime)

    @Test fun sortsNewestUnlockFirst() {
        val list = listOf(ach(1, 100), ach(2, 300), ach(3, 200))
        val sorted = list.sortedByUnlockedNewestFirst()
        assertEquals(listOf(2, 3, 1), sorted.map { it.id })
    }

    @Test fun tiesKeepSourceOrder() {
        val list = listOf(ach(1, 100), ach(2, 100), ach(3, 0), ach(4, 0))
        val sorted = list.sortedByUnlockedNewestFirst()
        assertEquals(listOf(1, 2, 3, 4), sorted.map { it.id })
    }
}
