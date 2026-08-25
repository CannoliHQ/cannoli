package dev.cannoli.scorza.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Installed Cores used to be hidden unless an externally installed RetroArch was present, from when
 * RetroArch was a separate app and the cores were its own. The embedded runner keeps cores in
 * `filesDir`, so Cannoli always has some, and on a device with no external RetroArch the gate hid
 * the only screen that lists them. Robolectric installs no such package, which is exactly the
 * device it broke on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmulationSettingsRowsTest {

    private fun emulationRowKeys(): List<String> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The storage step normally chooses this; reading it unset is a deliberate error.
        val settings = dev.cannoli.scorza.settings.SettingsRepository(ctx).apply {
            sdCardRoot = java.io.File(ctx.cacheDir, "sd-root").apply { mkdirs() }.absolutePath
        }
        val vm = SettingsViewModel(
            settings = settings,
            appFonts = mockk(relaxed = true),
            context = ctx,
            rommStore = mockk(relaxed = true),
            pathsProvider = mockk(relaxed = true),
        )
        vm.load()
        vm.reinitialize(ctx.packageManager, ctx.packageName)
        val index = vm.state.value.categories.indexOfFirst { it.key == "emulation" }
        assertTrue("no emulation category", index >= 0)
        vm.setCategoryIndex(index)
        vm.enterCategory()
        return vm.state.value.items.map { it.key }
    }

    @Test fun `installed cores is listed with no external RetroArch present`() {
        assertTrue("installed_cores is missing", "installed_cores" in emulationRowKeys())
    }

    @Test fun `the emulator mapping row stays first`() {
        assertEquals("core_mapping", emulationRowKeys().firstOrNull())
    }
}
