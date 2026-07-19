package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.MainActivity
import dev.cannoli.scorza.util.ErrorLog
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * A persistent black task behind games on the larger display.
 * Games naturally cover it while running; when it becomes visible again it returns input focus
 * to the launcher task on the smaller display.
 */
@AndroidEntryPoint
class BlackGameScreenActivity : ComponentActivity() {

    @Inject lateinit var activityDisplayRouter: ActivityDisplayRouter
    private lateinit var tapGestureDetector: BlackScreenTapGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)
        tapGestureDetector = BlackScreenTapGestureDetector(
            ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        )
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        hideSystemUI()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        window.decorView.post(::returnFocusToLauncher)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        window.decorView.post(::hideSystemUI)
    }

    override fun onDestroy() {
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (tapGestureDetector.onTouch(
                actionMasked = event.actionMasked,
                x = event.x,
                y = event.y,
                pointerCount = event.pointerCount,
            )
        ) {
            window.decorView.post(::returnFocusToLauncher)
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            window.decorView.post(::returnFocusToLauncher)
        }
        return true
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Suppress("DEPRECATION")
    private fun returnFocusToLauncher() {
        val launcherDisplayId = activityDisplayRouter.preferredLauncherDisplayId() ?: return
        if (launcherDisplayId == windowManager.defaultDisplay.displayId) return
        val launcherIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        try {
            startActivity(launcherIntent, noAnimationActivityOptions(launcherDisplayId))
            window.decorView.postDelayed(::hideSystemUI, SYSTEM_UI_REHIDE_DELAY_MS)
        } catch (e: RuntimeException) {
            ErrorLog.error("black game screen focus return failed", e)
        }
    }

    companion object {
        private const val SYSTEM_UI_REHIDE_DELAY_MS = 150L

        @Volatile
        private var activeActivity: WeakReference<BlackGameScreenActivity>? = null

        @Suppress("DEPRECATION")
        fun isShowingOn(displayId: Int): Boolean =
            activeActivity?.get()?.let { activity ->
                !activity.isFinishing && activity.windowManager.defaultDisplay.displayId == displayId
            } == true

        fun finishIfRunning() {
            activeActivity?.get()?.finishAndRemoveTask()
            activeActivity = null
        }

        fun intent(context: Context): Intent =
            Intent(context, BlackGameScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}

internal fun shouldBlankGameScreen(
    experimentalFeatures: Boolean,
    dualScreenLaunching: Boolean,
    topScreenBlackout: Boolean,
    gameDisplayId: Int?,
    launcherDisplayId: Int,
): Boolean = experimentalFeatures &&
    dualScreenLaunching &&
    topScreenBlackout &&
    gameDisplayId != null &&
    gameDisplayId != launcherDisplayId

/**
 * Defers the focus handoff until Android has delivered a complete tap. Moving focus on
 * ACTION_DOWN interrupts the pointer stream and can make the system's gesture monitor treat an
 * ordinary touch as a Home swipe, which replaces the launcher task and tears down the game.
 */
internal class BlackScreenTapGestureDetector(private val touchSlop: Float) {
    private var trackingTap = false
    private var downX = 0f
    private var downY = 0f

    fun onTouch(
        actionMasked: Int,
        x: Float,
        y: Float,
        pointerCount: Int = 1,
    ): Boolean = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            trackingTap = pointerCount == 1
            downX = x
            downY = y
            false
        }
        MotionEvent.ACTION_MOVE -> {
            if (trackingTap && movedBeyondTouchSlop(x, y)) trackingTap = false
            false
        }
        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
            trackingTap = false
            false
        }
        MotionEvent.ACTION_UP -> {
            val completedTap = trackingTap && pointerCount == 1 && !movedBeyondTouchSlop(x, y)
            trackingTap = false
            completedTap
        }
        else -> false
    }

    private fun movedBeyondTouchSlop(x: Float, y: Float): Boolean {
        val deltaX = x - downX
        val deltaY = y - downY
        return deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop
    }
}
