package dev.cannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ArtworkLookup
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
class AppsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: AppsRepository

    @Before fun setUp() {
        root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        val paths = CannoliPathsProvider(settings)
        repo = AppsRepository(CannoliDatabase(paths), ArtworkLookup(paths))
    }

    private fun writeArt(tag: String, fileName: String): File {
        val dir = File(root, "Art/$tag").apply { mkdirs() }
        return File(dir, fileName).apply { writeBytes(ByteArray(4)) }
    }

    @Test fun `tool art resolves from the TOOLS directory`() {
        val art = writeArt("TOOLS", "Termux.png")
        val id = repo.upsert(AppType.TOOL, "Termux", "com.termux")
        assertEquals(art, repo.byId(id)?.artFile)
    }

    @Test fun `port art resolves from the PORTS directory`() {
        val art = writeArt("PORTS", "Portal 2.jpg")
        val id = repo.upsert(AppType.PORT, "Portal 2", "com.valve.portal2")
        assertEquals(art, repo.byId(id)?.artFile)
    }

    @Test fun `artFile is null when no art exists`() {
        val id = repo.upsert(AppType.TOOL, "Termux", "com.termux")
        assertNull(repo.byId(id)?.artFile)
    }

    @Test fun `a tool does not pick up art from the ports directory`() {
        writeArt("PORTS", "Termux.png")
        val id = repo.upsert(AppType.TOOL, "Termux", "com.termux")
        assertNull(repo.byId(id)?.artFile)
    }

    @Test fun `all resolves art for every row`() {
        val art = writeArt("TOOLS", "Termux.png")
        repo.upsert(AppType.TOOL, "Termux", "com.termux")
        repo.upsert(AppType.TOOL, "No Art", "com.example.noart")
        val byName = repo.all(AppType.TOOL).associateBy { it.displayName }
        assertEquals(art, byName["Termux"]?.artFile)
        assertNull(byName["No Art"]?.artFile)
    }
}
