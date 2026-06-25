package dev.cannoli.scorza.config

import org.junit.Assert.assertTrue
import org.junit.Test

class CannoliPathsOfflineTest {
    @Test
    fun offlineDir_isUnderConfigRetroAchievements() {
        val paths = CannoliPaths("/sd")
        assertTrue(paths.configRaOffline.path.endsWith("/Config/RetroAchievements/Offline"))
    }
}
