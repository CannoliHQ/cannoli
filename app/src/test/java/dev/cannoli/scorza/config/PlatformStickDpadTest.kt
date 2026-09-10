package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformStickDpadTest {

    private val assets = ApplicationProvider
        .getApplicationContext<android.content.Context>().assets

    @Test
    fun `the forced stick flag is read back per tag`() {
        val config = PlatformConfig({ File("/tmp/cannoli-stick-test") }, assets)

        assertTrue(config.forcesStickDpad("NDS"))
        assertFalse(config.forcesStickDpad("GBA"))
    }
}
