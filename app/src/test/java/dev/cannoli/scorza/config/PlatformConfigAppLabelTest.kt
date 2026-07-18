package dev.cannoli.scorza.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigAppLabelTest {

    @Test fun `Citra MMJ stays distinguishable when both apps call themselves Citra`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        installPackage(pm, "org.citra.citra_emu", "Citra")
        installPackage(pm, "org.citra.emu", "Citra")
        val config = PlatformConfig(File(context.cacheDir, "citra-label-root"), context.assets)

        val options = config.getCorePickerOptions("3DS", pm = pm)
        val upstream = options.first { it.appPackage == "org.citra.citra_emu" }
        val mmj = options.first { it.appPackage == "org.citra.emu" }

        assertEquals("Citra", upstream.displayName)
        assertEquals("Citra MMJ", mmj.displayName)
        assertNotEquals(upstream.displayName, mmj.displayName)
    }

    private fun installPackage(pm: android.content.pm.PackageManager, packageName: String, label: String) {
        shadowOf(pm).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        })
    }
}
