package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.MainActivity
import dev.cannoli.scorza.util.LaunchLog
import javax.inject.Inject

internal enum class GameSessionResumeAction {
    LAUNCH_GAME,
    WAIT_FOR_GAME,
    RETURN_TO_LAUNCHER,
}

internal class GameSessionLifecycle(
    private var launchDispatched: Boolean = false,
    private var gameTookFocus: Boolean = false,
) {
    fun onResume(): GameSessionResumeAction = when {
        !launchDispatched -> {
            launchDispatched = true
            GameSessionResumeAction.LAUNCH_GAME
        }
        gameTookFocus -> GameSessionResumeAction.RETURN_TO_LAUNCHER
        else -> GameSessionResumeAction.WAIT_FOR_GAME
    }

    fun onPause() {
        if (launchDispatched) gameTookFocus = true
    }

    fun save(outState: Bundle) {
        outState.putBoolean(STATE_LAUNCH_DISPATCHED, launchDispatched)
        outState.putBoolean(STATE_GAME_TOOK_FOCUS, gameTookFocus)
    }

    companion object {
        private const val STATE_LAUNCH_DISPATCHED = "launchDispatched"
        private const val STATE_GAME_TOOK_FOCUS = "gameTookFocus"

        fun restore(savedInstanceState: Bundle?): GameSessionLifecycle = GameSessionLifecycle(
            launchDispatched = savedInstanceState?.getBoolean(STATE_LAUNCH_DISPATCHED) == true,
            gameTookFocus = savedInstanceState?.getBoolean(STATE_GAME_TOOK_FOCUS) == true,
        )
    }
}

/**
 * A short-lived task placed behind an externally launched game on the game display.
 * When that game exits, this activity resumes and explicitly focuses the existing launcher task
 * on the preferred launcher display.
 */
@AndroidEntryPoint
class ExternalGameSessionActivity : ComponentActivity() {

    @Inject lateinit var activityDisplayRouter: ActivityDisplayRouter

    private lateinit var sessionLifecycle: GameSessionLifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionLifecycle = GameSessionLifecycle.restore(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        when (sessionLifecycle.onResume()) {
            GameSessionResumeAction.LAUNCH_GAME -> window.decorView.post(::launchGame)
            GameSessionResumeAction.RETURN_TO_LAUNCHER -> window.decorView.post(::returnToLauncher)
            GameSessionResumeAction.WAIT_FOR_GAME -> Unit
        }
    }

    override fun onPause() {
        sessionLifecycle.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessionLifecycle.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun launchGame() {
        val target = intent.externalGameTargetIntent() ?: run {
            LaunchLog.write("game session missing target intent")
            returnToLauncher()
            return
        }
        // Keep the emulator above this dedicated session task. Removing task-creation flags makes
        // returning to this activity deterministic when the emulator closes.
        target.removeFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_TASK_ON_HOME
        )
        target.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val component = target.component?.flattenToShortString()
            ?: target.`package`
            ?: target.action
            ?: "<implicit>"
        LaunchLog.write("game session launching target=$component display=$currentDisplayId")

        val previousVmPolicy = if (target.data?.scheme == "file") {
            val current = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            current
        } else null
        try {
            startActivity(target, noAnimationActivityOptions())
        } catch (e: Exception) {
            LaunchLog.error("game session target failed component=$component", e)
            returnToLauncher()
        } finally {
            previousVmPolicy?.let(StrictMode::setVmPolicy)
        }
    }

    private fun returnToLauncher() {
        val launcherDisplayId = activityDisplayRouter.preferredLauncherDisplayId(
            forcePrimaryWhenDisabled = true
        )
        val launcherIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_GAME_SESSION_RETURN, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        LaunchLog.write(
            "game session returning focus launcherDisplay=${launcherDisplayId ?: "system-default"}"
        )
        try {
            startActivity(launcherIntent, noAnimationActivityOptions(launcherDisplayId))
        } catch (e: RuntimeException) {
            LaunchLog.error("game session launcher focus failed", e)
        }
        finishAndRemoveTask()
    }

    @Suppress("DEPRECATION")
    private val currentDisplayId: Int
        get() = windowManager.defaultDisplay.displayId

    companion object {
        const val EXTRA_GAME_SESSION_RETURN = "dev.cannoli.scorza.extra.GAME_SESSION_RETURN"
        private const val EXTRA_TARGET_INTENT = "dev.cannoli.scorza.extra.GAME_TARGET_INTENT"

        fun wrap(context: Context, target: Intent, gameDisplayId: Int?): Intent {
            if (gameDisplayId == null) return target
            return Intent(context, ExternalGameSessionActivity::class.java).apply {
                putExtra(EXTRA_TARGET_INTENT, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
        }

        @Suppress("DEPRECATION")
        internal fun Intent.externalGameTargetIntent(): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
            } else {
                getParcelableExtra(EXTRA_TARGET_INTENT)
            }
    }
}
