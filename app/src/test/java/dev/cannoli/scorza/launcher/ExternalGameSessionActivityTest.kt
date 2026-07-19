package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.libretro.LibretroActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalGameSessionActivityTest {

    @Test fun `embedded games stay in the black session task`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, LibretroActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)
    }

    @Test fun `single-screen launches keep their original intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = Intent("test.game")

        assertSame(target, ExternalGameSessionActivity.wrap(context, target, null))
    }

    @Test fun `dual-screen launches use a session task that preserves the game intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = Intent("test.game").putExtra("ROM", "/roms/game.3ds")

        val session = ExternalGameSessionActivity.wrap(context, target, 7)
        val restored = ExternalGameSessionActivity.run { session.externalGameTargetIntent() }

        assertEquals(ExternalGameSessionActivity::class.java.name, session.component?.className)
        assertEquals("test.game", restored?.action)
        assertEquals("/roms/game.3ds", restored?.getStringExtra("ROM"))
    }

    @Test fun `session returns only after the launched game has taken focus and closed`() {
        val lifecycle = GameSessionLifecycle()

        assertEquals(GameSessionResumeAction.LAUNCH_GAME, lifecycle.onResume())
        assertEquals(GameSessionResumeAction.WAIT_FOR_GAME, lifecycle.onResume())
        lifecycle.onPause()
        assertEquals(GameSessionResumeAction.RETURN_TO_LAUNCHER, lifecycle.onResume())
    }
}
