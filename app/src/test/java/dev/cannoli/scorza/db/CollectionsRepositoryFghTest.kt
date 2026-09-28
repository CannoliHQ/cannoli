package dev.cannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.settings.SettingsRepository
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
class CollectionsRepositoryFghTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var repo: CollectionsRepository

    @Before fun setUp() {
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        repo = CollectionsRepository(CannoliDatabase(CannoliPathsProvider(settings)))
    }

    @Test fun `favorites is offered first, then the standard collections`() {
        val rpgs = repo.create("RPGs")
        val favorites = repo.create("Favorites", CollectionType.FAVORITES)
        val action = repo.create("Action")

        assertEquals(listOf(favorites, rpgs, action), repo.fghChoices().map { it.id })
    }

    @Test fun `favorites is accepted as the chosen collection`() {
        repo.create("RPGs")
        val favorites = repo.create("Favorites", CollectionType.FAVORITES)

        assertEquals(favorites, repo.resolveFghCollection(favorites)?.id)
    }

    @Test fun `a missing collection still falls back to the first standard collection`() {
        repo.create("Favorites", CollectionType.FAVORITES)
        val rpgs = repo.create("RPGs")
        val gone = repo.create("Gone")
        repo.delete(gone)

        assertEquals(rpgs, repo.resolveFghCollection(gone)?.id)
        assertEquals(rpgs, repo.resolveFghCollection(null)?.id)
    }

    @Test fun `with no standard collection the fallback is favorites`() {
        val favorites = repo.create("Favorites", CollectionType.FAVORITES)

        assertEquals(favorites, repo.resolveFghCollection(null)?.id)
    }

    @Test fun `with no collections at all there is nothing to fall back to`() {
        assertNull(repo.resolveFghCollection(42))
    }
}
