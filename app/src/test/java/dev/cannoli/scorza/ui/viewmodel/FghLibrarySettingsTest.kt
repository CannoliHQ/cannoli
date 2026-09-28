package dev.cannoli.scorza.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.R
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class FghLibrarySettingsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var ctx: Context
    private lateinit var settings: SettingsRepository
    private lateinit var collections: CollectionsRepository

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        settings = SettingsRepository(ctx).apply { sdCardRoot = root.absolutePath }
        collections = CollectionsRepository(CannoliDatabase(CannoliPathsProvider(settings)))
    }

    private fun viewModel(): SettingsViewModel {
        val vm = SettingsViewModel(
            settings = settings,
            appFonts = mockk(relaxed = true),
            context = ctx,
            rommStore = mockk(relaxed = true),
            pathsProvider = mockk(relaxed = true),
        )
        vm.load()
        vm.reinitialize(ctx.packageManager, ctx.packageName, collections)
        return vm
    }

    private fun SettingsViewModel.openLibrary() {
        setCategoryIndex(state.value.categories.indexOfFirst { it.key == SettingsCategory.LIBRARY })
        enterCategory()
    }

    private fun SettingsViewModel.select(key: SettingsKey) {
        val index = state.value.items.indexOfFirst { it.key == key.id }
        assertTrue("${key.id} is not listed", index >= 0)
        moveSelection(index - state.value.selectedIndex)
    }

    private fun libraryKeys(mode: ContentMode): List<String> {
        settings.contentMode = mode
        val vm = viewModel()
        vm.openLibrary()
        return vm.state.value.items.map { it.key }
    }

    @Test fun `the ports and tools row sits under the collection row in five game handheld`() {
        val keys = libraryKeys(ContentMode.FIVE_GAME_HANDHELD)
        val collectionRow = keys.indexOf(SettingsKey.FGH_COLLECTION.id)
        assertTrue(collectionRow >= 0)
        assertEquals(SettingsKey.FGH_SHOW_PORTS_AND_TOOLS.id, keys.getOrNull(collectionRow + 1))
    }

    @Test fun `the ports and tools row is absent outside five game handheld`() {
        assertFalse(SettingsKey.FGH_SHOW_PORTS_AND_TOOLS.id in libraryKeys(ContentMode.PLATFORMS))
        assertFalse(SettingsKey.FGH_SHOW_PORTS_AND_TOOLS.id in libraryKeys(ContentMode.COLLECTIONS))
    }

    @Test fun `the ports and tools row cycles between hide and show`() {
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        val vm = viewModel()
        vm.openLibrary()
        vm.select(SettingsKey.FGH_SHOW_PORTS_AND_TOOLS)
        fun row() = vm.state.value.items.first { it.key == SettingsKey.FGH_SHOW_PORTS_AND_TOOLS.id }

        assertEquals(R.string.value_hide, row().valueRes)
        vm.cycleSelected(1)
        assertTrue(settings.fghShowPortsAndTools)
        assertEquals(R.string.value_show, row().valueRes)
        vm.cycleSelected(-1)
        assertFalse(settings.fghShowPortsAndTools)
    }

    @Test fun `the collection picker offers favorites first`() {
        val rpgs = collections.create("RPGs")
        val favorites = collections.create("Favorites", CollectionType.FAVORITES)
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        settings.fghCollectionId = rpgs
        val vm = viewModel()
        vm.openLibrary()
        vm.enterSubCategory(SettingsCategory.FGH_COLLECTION_PICKER, R.string.setting_fgh_collection)

        val items = vm.state.value.items
        assertEquals(
            listOf(SettingsKey.FGH_PICK_PREFIX + favorites, SettingsKey.FGH_PICK_PREFIX + rpgs),
            items.map { it.key },
        )
        assertEquals(R.string.label_favorites, items[0].labelRes)
        assertNull(items[0].labelText)
    }

    @Test fun `favorites stays chosen and is labelled as favorites`() {
        collections.create("RPGs")
        val favorites = collections.create("Favorites", CollectionType.FAVORITES)
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        settings.fghCollectionId = favorites
        val vm = viewModel()
        vm.openLibrary()

        val row = vm.state.value.items.first { it.key == SettingsKey.FGH_COLLECTION.id }
        assertEquals(favorites, settings.fghCollectionId)
        assertEquals(R.string.label_favorites, row.valueRes)
        assertNull(row.valueText)
    }

    @Test fun `cycling the collection row reaches favorites`() {
        val rpgs = collections.create("RPGs")
        val favorites = collections.create("Favorites", CollectionType.FAVORITES)
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        settings.fghCollectionId = rpgs
        val vm = viewModel()
        vm.openLibrary()
        vm.select(SettingsKey.FGH_COLLECTION)

        vm.cycleSelected(-1)
        assertEquals(favorites, settings.fghCollectionId)
    }

    @Test fun `a deleted collection falls back to the first standard collection`() {
        collections.create("Favorites", CollectionType.FAVORITES)
        val rpgs = collections.create("RPGs")
        val gone = collections.create("Gone")
        collections.delete(gone)
        settings.contentMode = ContentMode.FIVE_GAME_HANDHELD
        settings.fghCollectionId = gone
        val vm = viewModel()
        vm.openLibrary()

        assertEquals(rpgs, settings.fghCollectionId)
    }
}
