package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDownloadHandlerTest {

    private fun item(core: String) = DownloadItem(
        key = CoreDownloadHandler.keyFor(core),
        displayName = core,
        kind = DownloadKind.CORE,
        payload = core,
    )

    private fun handler(ok: Boolean, systemFor: MutableList<String>) = CoreDownloadHandler(
        fetchCore = { id, _ -> CoreDownloadService.Result("core", id, ok, if (ok) null else "404") },
        fetchSystemFiles = { id -> systemFor += id },
    )

    @Test fun `a downloaded core brings its system files with it`() {
        val fetched = mutableListOf<String>()

        handler(ok = true, systemFor = fetched).run(item("armsx2_libretro"), { _, _ -> }, { false })

        assertEquals(listOf("armsx2_libretro"), fetched)
    }

    @Test fun `a failed core download fetches no system files`() {
        val fetched = mutableListOf<String>()

        val failed = runCatching {
            handler(ok = false, systemFor = fetched).run(item("armsx2_libretro"), { _, _ -> }, { false })
        }.isFailure

        assertTrue(failed)
        assertTrue(fetched.isEmpty())
    }
}
