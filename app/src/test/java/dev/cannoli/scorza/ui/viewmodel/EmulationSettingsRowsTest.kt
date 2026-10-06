package dev.cannoli.scorza.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmulationSettingsRowsTest {

    private fun emulationRowKeys(): List<String> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
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
        val index = vm.state.value.categories.indexOfFirst { it.key == SettingsCategory.EMULATION }
        assertTrue("no emulation category", index >= 0)
        vm.setCategoryIndex(index)
        vm.enterCategory()
        return vm.state.value.items.map { it.key }
    }

    @Test fun `the core row is listed with no external RetroArch present`() {
        assertTrue("update_cores is missing", "update_cores" in emulationRowKeys())
    }

    @Test fun `a stopped run reads differently from a finished one`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun valueFor(completed: Boolean): String? {
            val settings = dev.cannoli.scorza.settings.SettingsRepository(ctx).apply {
                sdCardRoot = java.io.File(ctx.cacheDir, "sd-root").apply { mkdirs() }.absolutePath
                lastCoreUpdate = "2026-08-25"
                lastCoreUpdateCompleted = completed
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
            val index = vm.state.value.categories.indexOfFirst { it.key == SettingsCategory.EMULATION }
            vm.setCategoryIndex(index)
            vm.enterCategory()
            return vm.state.value.items.first { it.key == SettingsKey.UPDATE_CORES.id }.valueText
        }

        val finished = valueFor(true)
        val stopped = valueFor(false)
        assertTrue("a finished run should read as a run: $finished", finished?.contains("Last run") == true)
        assertTrue("a stopped run should say so: $stopped", stopped?.contains("Stopped") == true)
    }

    @Test fun `the core row is an action, so the screen can give it a legend`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
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
        val index = vm.state.value.categories.indexOfFirst { it.key == SettingsCategory.EMULATION }
        vm.setCategoryIndex(index)
        vm.enterCategory()
        val row = vm.state.value.items.first { it.key == SettingsKey.UPDATE_CORES.id }

        assertTrue("not marked as an action", row.isAction)
        assertTrue("an action row must not also cycle", !row.canCycle)
        assertTrue("the status is what makes it need the flag", row.valueText != null)
    }

    @Test fun `the emulator mapping row stays first`() {
        assertEquals("core_mapping", emulationRowKeys().firstOrNull())
    }
}
