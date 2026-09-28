package dev.cannoli.scorza.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
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
class FghFavoritesCollectionTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var settings: SettingsRepository
    private lateinit var collections: CollectionsRepository
    private val systemList = mockk<SystemListViewModel>(relaxed = true)
    private val gameList = mockk<GameListViewModel>(relaxed = true)
    private lateinit var actions: LauncherActions

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        settings = SettingsRepository(ctx).apply { sdCardRoot = root.absolutePath }
        collections = CollectionsRepository(CannoliDatabase(CannoliPathsProvider(settings)))
        actions = LauncherActions(
            context = ctx,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            settings = settings,
            collectionsRepository = collections,
            recentlyPlayedRepository = mockk(relaxed = true),
            romsRepository = mockk(relaxed = true),
            appsRepository = mockk(relaxed = true),
            launchManager = mockk(relaxed = true),
            platformConfig = mockk(relaxed = true),
            gameOverrideStore = mockk(relaxed = true),
            artworkLookup = mockk(relaxed = true),
            arcadeTitleLookup = mockk(relaxed = true),
            nav = mockk(relaxed = true),
            systemListViewModel = systemList,
            gameListViewModel = gameList,
            settingsViewModel = mockk(relaxed = true),
            saveSyncService = mockk(relaxed = true),
            pathsProvider = mockk(relaxed = true),
        )
    }

    @Test fun `favorites is accepted as the five game handheld collection`() {
        collections.create("RPGs")
        val favorites = collections.create("Favorites", CollectionType.FAVORITES)
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        settings.fghCollectionId = favorites

        assertEquals(favorites, actions.validateFghCollection())
        assertEquals(favorites, settings.fghCollectionId)
    }

    @Test fun `stars stay off in five game handheld with favorites chosen`() {
        val favorites = collections.create("Favorites", CollectionType.FAVORITES)
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        settings.fghCollectionId = favorites
        settings.fghShowPortsAndTools = true

        actions.rescanSystemList(scanDisk = false)

        verify { gameList.showFavoriteStars = false }
        val config = slot<SystemListViewModel.ScanConfig>()
        verify { systemList.scan(capture(config), false, false, any(), any()) }
        assertEquals(favorites, config.captured.fghCollectionId)
        assertEquals(true, config.captured.fghShowPortsAndTools)
    }
}
