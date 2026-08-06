package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigSourcesTest {
    private val RA = "com.retroarch"

    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlatformConfig(File(ctx.cacheDir, "src-root").apply { mkdirs() }, ctx.assets)
    }

    @Test fun `RetroArch is available whenever the platform has candidate cores`() {
        val sources = config().availableSources("NES")
        assertTrue(EmulatorSource.RetroArch in sources)
    }

    @Test fun `a platform with no standalone apps does not list Standalone`() {
        val sources = config().availableSources("32X")
        assertFalse(EmulatorSource.Standalone in sources)
    }

    @Test fun `when RetroArch cannot report, every candidate core is offered as unknown regardless of includeAll`() {
        val pc = config()
        val installedOnly = pc.emulatorOptionsForSource(
            "NES", EmulatorSource.RetroArch, includeAll = false,
            externalRaPackages = listOf(RA), unreportableRaPackages = setOf(RA),
        )
        val all = pc.emulatorOptionsForSource(
            "NES", EmulatorSource.RetroArch, includeAll = true,
            externalRaPackages = listOf(RA), unreportableRaPackages = setOf(RA),
        )
        assertTrue("installed-only must not hide cores it cannot rule out", installedOnly.isNotEmpty())
        assertEquals(
            "the toggle must not change a list that cannot be filtered",
            all.map { it.coreId }.toSet(), installedOnly.map { it.coreId }.toSet(),
        )
        installedOnly.forEach { assertEquals(CoreAvailability.UNKNOWN, it.availability) }
    }

    @Test fun `a reported core stays available even while the package cannot report others`() {
        val pc = config()
        val candidate = "nestopia_libretro"
        val options = pc.emulatorOptionsForSource(
            "NES", EmulatorSource.RetroArch, includeAll = false,
            installedRaCores = mapOf(RA to setOf(candidate)),
            externalRaPackages = listOf(RA), unreportableRaPackages = setOf(RA),
        )
        val reported = options.firstOrNull { it.coreId == candidate }
        assertTrue("the reported core must still be listed", reported != null)
        assertEquals(CoreAvailability.AVAILABLE, reported!!.availability)
    }

    // Each install is its own set of choices, so a core present in two of them is two rows and
    // each row names the package it came from.
    @Test fun `every external install contributes its own rows`() {
        val pc = config()
        val candidate = "nestopia_libretro"
        val other = "com.retroarch.aarch64"
        val options = pc.emulatorOptionsForSource(
            "NES", EmulatorSource.RetroArch, includeAll = false,
            installedRaCores = mapOf(RA to setOf(candidate), other to setOf(candidate)),
            externalRaPackages = listOf(RA, other),
        )
        val forCandidate = options.filter { it.coreId == candidate }
        assertEquals(2, forCandidate.size)
        assertEquals(setOf(RA, other), forCandidate.mapNotNull { it.appPackage }.toSet())
    }

    @Test fun `no external installs means no RetroArch rows at all`() {
        val options = config().emulatorOptionsForSource(
            "NES", EmulatorSource.RetroArch, includeAll = true,
        )
        assertTrue("nothing to run them, so nothing to offer", options.isEmpty())
    }

    @Test fun `getFirmwareStatus reports presence per firmware entry against the bios dir`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coreInfo = CoreInfoRepository(ctx.assets)
        coreInfo.load()
        val pc = PlatformConfig(File(ctx.cacheDir, "fw-root").apply { mkdirs() }, ctx.assets, coreInfo)

        val coreId = "a5200_libretro"
        val expected = coreInfo.getFirmwareFor(coreId)
        assertTrue("a5200 core_info should declare firmware", expected.isNotEmpty())

        val biosDir = File(ctx.cacheDir, "fw-bios").apply { mkdirs() }
        val missing = pc.getFirmwareStatus(coreId, biosDir)
        assertTrue(missing.isNotEmpty())
        assertTrue("no firmware files present yet", missing.all { !it.second })

        val firstPath = expected.first().path
        File(biosDir, firstPath).apply { parentFile?.mkdirs() }.writeText("stub")
        val afterPlacing = pc.getFirmwareStatus(coreId, biosDir)
        assertTrue("placed firmware is reported present",
            afterPlacing.first { it.first.path == firstPath }.second)
    }
}
