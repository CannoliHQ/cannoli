package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.igm.RICOTTA_PROTOCOL_VERSION
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetroArchLauncherTest {

    @Test fun `stock RetroArch keeps launch extras on configured game display`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(base.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.retroarch.aarch64"
            activities = arrayOf(ActivityInfo().apply {
                packageName = "com.retroarch.aarch64"
                name = "com.retroarch.browser.retroactivity.RetroActivityFuture"
                exported = true
            })
        })
        val context = RecordingContext(base)
        val displayRouter = mockk<ActivityDisplayRouter>().apply {
            every { gameLaunchDisplayId() } returns 7
            every { diagnosticSummary() } returns "test-displays"
        }
        val rom = File(base.cacheDir, "launch-test.rom").apply { writeText("rom") }
        val launcher = RetroArchLauncher(context, { "com.retroarch.aarch64" }, displayRouter)

        val result = launcher.launchRetroArchIntent(rom, "snes9x", "/tmp/retroarch.cfg")

        assertEquals(LaunchResult.Success, result)
        val sessionIntent = context.startedIntent
        assertEquals(
            ExternalGameSessionActivity::class.java.name,
            sessionIntent?.component?.className,
        )
        val targetIntent = sessionIntent?.let {
            ExternalGameSessionActivity.run { it.externalGameTargetIntent() }
        }
        assertEquals(rom.absolutePath, targetIntent?.getStringExtra("ROM"))
        assertEquals(
            "/data/data/com.retroarch.aarch64/cores/snes9x_android.so",
            targetIntent?.getStringExtra("LIBRETRO"),
        )
        assertNotNull(context.startedOptions)
        assertEquals(
            7,
            context.startedOptions?.getInt("android.activity.launchDisplayId", Display.INVALID_DISPLAY),
        )
    }

    @Test fun `gate fails when installed protocol is older`() {
        val verdict = RetroArchLauncher.checkProtocol(
            installedProtocol = RICOTTA_PROTOCOL_VERSION - 1,
            requiredProtocol = RICOTTA_PROTOCOL_VERSION,
        )
        assertTrue(verdict is ProtocolVerdict.UpdateRicotta)
    }

    @Test fun `gate fails asking to update cannoli when installed is newer`() {
        val verdict = RetroArchLauncher.checkProtocol(
            installedProtocol = RICOTTA_PROTOCOL_VERSION + 1,
            requiredProtocol = RICOTTA_PROTOCOL_VERSION,
        )
        assertTrue(verdict is ProtocolVerdict.UpdateCannoli)
    }

    @Test fun `gate passes on exact match`() {
        val verdict = RetroArchLauncher.checkProtocol(
            installedProtocol = RICOTTA_PROTOCOL_VERSION,
            requiredProtocol = RICOTTA_PROTOCOL_VERSION,
        )
        assertEquals(ProtocolVerdict.Ok, verdict)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null
        var startedOptions: Bundle? = null

        override fun startActivity(intent: Intent, options: Bundle?) {
            startedIntent = intent
            startedOptions = options
        }
    }
}
