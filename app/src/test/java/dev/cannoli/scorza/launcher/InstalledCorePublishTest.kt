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
        known: Set<String> = answered.keys + silent + unsupported,
    ) {
        val m = InstalledCoreService::class.java.getDeclaredMethod(
            "publishQueryResult", Map::class.java, Set::class.java, Set::class.java, Set::class.java,
        )
        m.isAccessible = true
        m.invoke(svc, answered, silent, unsupported, known)
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

    // This used to assert REPORTS, on the theory that an optimistic default stopped the notice
    // flashing during boot. That made "no information yet" indistinguishable from "reported",
    // so the picker labelled every core Not Installed until the boot scan landed.
    @Test fun `a package is unscanned until the first query publishes`() {
        assertEquals(CoreReporting.UNSCANNED, service().reportingFor(STOCK))
    }

    @Test fun `a package that is not installed is not mistaken for one that reported`() {
        val svc = service()
        publish(svc, answered = mapOf(RICOTTA to setOf("a_libretro")))
        assertEquals(CoreReporting.NOT_INSTALLED, svc.reportingFor(STOCK))
    }

    @Test fun `an uninstalled package survives a scan that found nothing at all`() {
        val svc = service()
        publish(svc, answered = emptyMap())
        assertTrue("a scan that discovers nothing still counts as a scan", svc.cacheReady)
        assertEquals(CoreReporting.NOT_INSTALLED, svc.reportingFor(STOCK))
    }

    @Test fun `canReport is false for every way of not knowing`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(RICOTTA), unsupported = setOf(STOCK))
        assertFalse("silent", svc.canReport(RICOTTA))
        assertFalse("no receiver", svc.canReport(STOCK))
        assertFalse("not installed", svc.canReport("com.retroarch.absent"))
        assertFalse("never scanned", service().canReport(STOCK))
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
