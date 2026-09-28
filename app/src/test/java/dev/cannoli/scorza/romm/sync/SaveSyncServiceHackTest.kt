package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.cache.GameRecord
import dev.cannoli.scorza.romm.cache.RommDatabase
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveSyncServiceHackTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var cache: RommDatabase
    private lateinit var service: SaveSyncService

    @Before fun setup() {
        val sd = tmp.newFolder("SD")
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = sd.absolutePath
        settings.rommSaveSyncEnabled = true
        settings.rommDeviceId = "dev-1"
        val paths = CannoliPathsProvider(settings)
        val db = CannoliDatabase(paths)
        val links = RommLinkRepository(db) { File(sd, "Roms") }
        val cacheFile = File(tmp.newFolder("Cache"), "romm.db")
        cache = RommDatabase { cacheFile }
        cache.replacePlatforms(listOf(RommPlatform(1, "nes", "NES", "NES", 1) to null))
        cache.upsertGames(listOf(GameRecord(
            RommGame(
                1343, 1, "Tecmo Super Bowl", "Tecmo Super Bowl", 0L, null, null, emptyList(), emptyList(), null,
                listOf(
                    RommFile("Tecmo Super Bowl (USA).nes", 1, null, null, null, id = 1832, category = "game", isTopLevel = true),
                    RommFile("Tecmo Super Bowl 2025.nes", 1, null, null, null, id = 1833, subDir = "hacks", category = "hack"),
                ),
            ),
            null,
        )))
        val connStore = mockk<RommConnectionStore>(relaxed = true)
        every { connStore.isConfigured } returns true
        every { connStore.serverVersion } returns "5.0.0"
        val registrar = mockk<DeviceRegistrar>(); every { registrar.deviceId() } returns "dev-1"
        val resolver = LocalSaveResolver(paths.root)
        service = SaveSyncService(
            mockk(relaxed = true), connStore, settings, registrar, SaveSyncStore(db), resolver, links, paths,
            SaveBackupManager(paths.root, resolver), SyncHistoryStore(db), PendingConflictStore(db),
            RestorePromotionStore(db), SaveSyncStatusHolder(), RommCacheMatcher(cache), mockk(relaxed = true),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
    }

    @After fun tearDown() = cache.close()

    @Test fun `the base game file of a hacks entry syncs as the entry`() {
        assertEquals(1343, service.isSyncableGame("NES/Tecmo Super Bowl (USA).nes"))
    }

    @Test fun `a hack file never syncs`() {
        assertNull(service.isSyncableGame("NES/Tecmo Super Bowl 2025.nes"))
    }
}
