package dev.cannoli.scorza.ui.screens

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.GameOverrideStore
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.input.EmulatorMappingBuilder
import dev.cannoli.scorza.launcher.CoreReporting
import dev.cannoli.scorza.launcher.InstalledCoreService
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// The notice must be inserted, use the right copy for the right reporting state, and sit
// directly under the RetroArch header. A hand-constructed MappingItem.Notice cannot catch any
// of that; only building the real screen through EmulatorMappingBuilder can.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MappingNoticeTest {

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    private class Fixture(val builder: EmulatorMappingBuilder, val raLabel: String)

    private fun fixture(name: String, reporting: CoreReporting): Fixture {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }

        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns File(root, "Roms").apply { mkdirs() }
        val db = CannoliDatabase(paths)

        val config = PlatformConfig(root, ctx.assets).also { it.load() }
        val settings = dev.cannoli.scorza.settings.SettingsRepository(ctx).also { it.sdCardRoot = root.absolutePath }
        val cores = mockk<InstalledCoreService>(relaxed = true)
        every { cores.configuredCores() } returns emptyMap()
        every { cores.configuredReporting() } returns reporting
        val store = GameOverrideStore(db)
        val raLabel = InstalledCoreService.getPackageLabel(settings.retroArchPackage)
        return Fixture(EmulatorMappingBuilder(config, cores, settings, store, ctx), raLabel)
    }

    private fun retroArchHeaderIndex(items: List<MappingItem>, raLabel: String): Int =
        items.indexOfFirst { it is MappingItem.SectionHeader && it.label == raLabel }

    @Test fun `the notice is never focusable`() {
        assertFalse(MappingItem.Notice("This RetroArch version cannot report installed cores").isSelectable)
    }

    @Test fun `no notice when RetroArch reports normally`() {
        val f = fixture("notice-reports", CoreReporting.REPORTS)
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true)
        assertTrue(screen.items.none { it is MappingItem.Notice })
    }

    @Test fun `unsupported RetroArch shows the cannot-report notice directly under its header`() {
        val f = fixture("notice-unsupported", CoreReporting.UNSUPPORTED)
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = ctx.getString(dev.cannoli.scorza.R.string.mapping_notice_cannot_report_cores)

        val headerAt = retroArchHeaderIndex(screen.items, f.raLabel)
        assertTrue("RetroArch header must be present", headerAt >= 0)
        val notice = screen.items[headerAt + 1]
        assertTrue(notice is MappingItem.Notice)
        assertEquals(expected, (notice as MappingItem.Notice).text)
    }

    @Test fun `a RetroArch that is not installed says so instead of flagging every core`() {
        val f = fixture("notice-absent", CoreReporting.NOT_INSTALLED)
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = ctx.getString(dev.cannoli.scorza.R.string.mapping_notice_not_installed)

        val headerAt = retroArchHeaderIndex(screen.items, f.raLabel)
        assertTrue("RetroArch header must be present", headerAt >= 0)
        val notice = screen.items[headerAt + 1]
        assertTrue(notice is MappingItem.Notice)
        assertEquals(expected, (notice as MappingItem.Notice).text)

        // The bug this fixes: every core row was stamped Not Installed, which is noise once the
        // notice has named the real problem.
        val raRows = screen.items.drop(headerAt).filterIsInstance<MappingItem.EmulatorOption>()
            .filter { it.option.source == dev.cannoli.scorza.config.EmulatorSource.RetroArch }
        assertTrue("the RetroArch section must still offer cores", raRows.isNotEmpty())
        raRows.forEach {
            assertEquals(CoreAvailability.UNKNOWN, it.option.availability)
        }
    }

    // A notice that appears mid-boot and then vanishes reads as a glitch, so the pre-scan window
    // stays silent. The rows still must not claim the cores are absent.
    @Test fun `an unscanned RetroArch shows no notice and claims nothing about its cores`() {
        val f = fixture("notice-unscanned", CoreReporting.UNSCANNED)
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true)
        assertTrue(screen.items.none { it is MappingItem.Notice })

        val headerAt = retroArchHeaderIndex(screen.items, f.raLabel)
        assertTrue("RetroArch header must be present", headerAt >= 0)
        val raRows = screen.items.drop(headerAt).filterIsInstance<MappingItem.EmulatorOption>()
            .filter { it.option.source == dev.cannoli.scorza.config.EmulatorSource.RetroArch }
        assertTrue(raRows.isNotEmpty())
        raRows.forEach {
            assertEquals(CoreAvailability.UNKNOWN, it.option.availability)
        }
    }

    @Test fun `silent RetroArch shows the no-response notice directly under its header`() {
        val f = fixture("notice-silent", CoreReporting.SILENT)
        val screen = f.builder.buildPlatformMapping("GBA", "GBA", showAll = true)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = ctx.getString(dev.cannoli.scorza.R.string.mapping_notice_no_response)

        val headerAt = retroArchHeaderIndex(screen.items, f.raLabel)
        assertTrue("RetroArch header must be present", headerAt >= 0)
        val notice = screen.items[headerAt + 1]
        assertTrue(notice is MappingItem.Notice)
        assertEquals(expected, (notice as MappingItem.Notice).text)
    }
}
