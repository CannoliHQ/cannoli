package dev.cannoli.igm

data class AchievementInfo(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    val state: Int = 0,
    val unlockTime: Long = 0,
    val pendingSync: Boolean = false
)

/** Newest unlock first; ties (equal unlockTime) keep their existing source order. */
fun List<AchievementInfo>.sortedByUnlockedNewestFirst(): List<AchievementInfo> =
    sortedByDescending { it.unlockTime }
