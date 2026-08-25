package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fbneo_libretro.info declares database_match_archive_member on the line above its real database
 * field. Matching that key by prefix read the decoy instead, so FinalBurn Neo claimed a database of
 * "false", was offered on no platform at all, and stayed invisible on the two that name it as their
 * default. A core whose database misparses becomes unreachable, so the first test below catches the
 * whole class rather than just this one file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreInfoParseTest {

    private val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private fun repo() = CoreInfoRepository(assets).also { it.load() }

    private fun shippedCoreIds(): List<String> =
        assets.list("core_info").orEmpty().filter { it.endsWith(".info") }.map { it.removeSuffix(".info") }

    @Test fun `every shipped core is offered on at least one platform`() {
        val repo = repo()
        val tags = PlatformConfig(
            java.io.File(
                ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
                "sd-root",
            ).apply { mkdirs() },
            assets,
        ).getAllTags()
        val offered = tags.flatMap { repo.getCoresForTag(it) }.map { it.id }.toSet()

        val unreachable = shippedCoreIds().filterNot { it in offered }
        assertEquals("shipped cores that no platform offers", emptyList<String>(), unreachable)
    }

    @Test fun `a database_ prefixed key does not mask the real database field`() {
        val repo = repo()
        assertTrue("fbneo_libretro" in repo.getCoresForTag("FBN").map { it.id })
        assertTrue("fbneo_libretro" in repo.getCoresForTag("NEOGEO").map { it.id })
        assertEquals("FinalBurn Neo", repo.getDisplayName("fbneo_libretro"))
    }
}
