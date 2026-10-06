package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreInfoRepositoryAbiTest {

    private val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private fun repo(abi: String) = CoreInfoRepository(assets, abi = { abi }).also { it.load() }

    @Test fun `an arm64-only core is offered on arm64-v8a`() {
        assertTrue("armsx2_libretro" in repo("arm64-v8a").getCoresForTag("PS2").map { it.id })
    }

    @Test fun `an arm64-only core is hidden on armeabi-v7a`() {
        assertFalse("armsx2_libretro" in repo("armeabi-v7a").getCoresForTag("PS2").map { it.id })
    }

    @Test fun `a core already mapped is untouched by the filter`() {
        val repo = repo("armeabi-v7a")
        assertFalse("armsx2_libretro" in repo.getCoresForTag("PS2").map { it.id })
        assertTrue(repo.getDisplayName("armsx2_libretro").isNotEmpty())
    }
}
