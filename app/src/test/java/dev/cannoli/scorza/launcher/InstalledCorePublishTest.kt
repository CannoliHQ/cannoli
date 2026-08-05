package dev.cannoli.scorza.launcher

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * publishQueryResult is private, so these drive it through the public surface: markInstalled
 * plus a reflective call to the publisher, which is the only way to simulate a query landing
 * without a live RetroArch to broadcast at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstalledCorePublishTest {

    private fun service(): InstalledCoreService {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return InstalledCoreService(ctx, SettingsRepository(ctx))
    }

    private fun publish(
        svc: InstalledCoreService,
        answered: Map<String, Set<String>>,
        silent: Set<String> = emptySet(),
        unsupported: Set<String> = emptySet(),
    ) {
        val m = InstalledCoreService::class.java.getDeclaredMethod(
            "publishQueryResult", Map::class.java, Set::class.java, Set::class.java,
        )
        m.isAccessible = true
        m.invoke(svc, answered, silent, unsupported)
    }

    @Test fun `a package that answers zero cores is installed, not unresponsive`() {
        val svc = service()
        publish(svc, answered = mapOf(RICOTTA to emptySet()))
        assertTrue(svc.installedCores.containsKey(RICOTTA))
        assertFalse("answering zero is a real answer", RICOTTA in svc.unresponsivePackages)
        assertTrue(svc.cacheReady)
    }

    @Test fun `a package that never answers is unresponsive`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(RICOTTA))
        assertTrue(RICOTTA in svc.unresponsivePackages)
    }

    // The blanket union used to keep a deleted core cached forever.
    @Test fun `a core removed inside retroarch drops out of the cache`() {
        val svc = service()
        publish(svc, answered = mapOf(RICOTTA to setOf("a_libretro", "b_libretro")))
        publish(svc, answered = mapOf(RICOTTA to setOf("a_libretro")))
        assertEquals(setOf("a_libretro"), svc.installedCores[RICOTTA])
    }

    // ...but the union was also protecting a download that landed mid-query, so that must survive.
    @Test fun `a core marked installed mid query is not clobbered by that query`() {
        val svc = service()
        publish(svc, answered = mapOf(RICOTTA to setOf("a_libretro")))
        svc.markInstalled(RICOTTA, "fresh_libretro")
        // A query that began before the download publishes a set without the new core.
        publish(svc, answered = mapOf(RICOTTA to setOf("a_libretro")))
        assertTrue("a just-downloaded core must survive a stale query", svc.hasCoreInPackage("fresh_libretro", RICOTTA))
    }

    @Test fun `a package that did not answer keeps its cached cores`() {
        val svc = service()
        publish(svc, answered = mapOf(RICOTTA to setOf("a_libretro")))
        publish(svc, answered = emptyMap(), silent = setOf(RICOTTA))
        assertEquals(setOf("a_libretro"), svc.installedCores[RICOTTA])
    }

    @Test fun `markInstalled clears the unresponsive flag`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(RICOTTA))
        svc.markInstalled(RICOTTA, "a_libretro")
        assertFalse(RICOTTA in svc.unresponsivePackages)
    }

    @Test fun `a package with no receiver is unsupported, not silent`() {
        val svc = service()
        publish(svc, answered = emptyMap(), unsupported = setOf(STOCK))
        assertTrue(STOCK in svc.unsupportedPackages)
        assertFalse("no receiver is a different fact from no answer", STOCK in svc.unresponsivePackages)
        assertEquals(CoreReporting.UNSUPPORTED, svc.reportingFor(STOCK))
    }

    @Test fun `a silent package reports as silent`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(STOCK))
        assertEquals(CoreReporting.SILENT, svc.reportingFor(STOCK))
    }

    @Test fun `an answering package reports as reporting`() {
        val svc = service()
        publish(svc, answered = mapOf(STOCK to setOf("a_libretro")))
        assertEquals(CoreReporting.REPORTS, svc.reportingFor(STOCK))
    }

    @Test fun `an unqueried package is assumed to report so no notice flashes before the first scan`() {
        assertEquals(CoreReporting.REPORTS, service().reportingFor(STOCK))
    }

    @Test fun `canReport is false for both ways of not knowing`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(RICOTTA), unsupported = setOf(STOCK))
        assertFalse(svc.canReport(STOCK))
        assertFalse(svc.canReport(RICOTTA))
    }

    @Test fun `an unsupported package that gains the receiver stops being unsupported`() {
        val svc = service()
        publish(svc, answered = emptyMap(), unsupported = setOf(STOCK))
        publish(svc, answered = mapOf(STOCK to setOf("a_libretro")))
        assertFalse(STOCK in svc.unsupportedPackages)
        assertEquals(CoreReporting.REPORTS, svc.reportingFor(STOCK))
    }

    private companion object {
        const val RICOTTA = "dev.cannoli.ricotta.aarch64"
        const val STOCK = "com.retroarch.aarch64"
    }
}
