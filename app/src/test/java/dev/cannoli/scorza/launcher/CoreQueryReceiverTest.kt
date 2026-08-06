package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreQueryReceiverTest {

    private val pm
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    private fun installReceiver(pkg: String) {
        val component = ComponentName(pkg, "$pkg.InstalledCoresReceiver")
        shadowOf(pm).addReceiverIfNotPresent(component)
        shadowOf(pm).addIntentFilterForReceiver(component, IntentFilter(ACTION_QUERY_INSTALLED_CORES))
    }

    @Test fun `a package declaring the receiver can be queried for cores`() {
        installReceiver(STOCK)
        assertTrue(pm.hasCoreQueryReceiver(STOCK))
    }

    @Test fun `a package without the receiver cannot be queried`() {
        assertFalse(pm.hasCoreQueryReceiver(STOCK))
    }

    @Test fun `the answer is scoped to the package asked about`() {
        installReceiver(RICOTTA)
        assertTrue(pm.hasCoreQueryReceiver(RICOTTA))
        assertFalse(pm.hasCoreQueryReceiver(STOCK))
    }

    private companion object {
        const val STOCK = "com.retroarch.aarch64"
        const val RICOTTA = "dev.cannoli.ricotta.aarch64"
    }
}
