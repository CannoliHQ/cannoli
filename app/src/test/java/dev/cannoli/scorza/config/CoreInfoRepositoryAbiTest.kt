package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * armsx2_libretro has no armeabi-v7a build, so it must be offered on arm64-v8a and hidden on
 * armeabi-v7a. Unit tests cannot set Build.SUPPORTED_ABIS, so the repository takes the ABI as a
 * provider instead.
 */
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
        // getCoresForTag only decides what is offered; a core a user already picked is looked up by
        // id directly, not re-derived from the tag list the filter narrows.
        val repo = repo("armeabi-v7a")
        assertFalse("armsx2_libretro" in repo.getCoresForTag("PS2").map { it.id })
        assertTrue(repo.getDisplayName("armsx2_libretro").isNotEmpty())
    }
}
