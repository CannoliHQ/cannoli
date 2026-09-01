package com.retroarch.browser.retroactivity

import android.app.Activity
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.cannoli.ui.computeScreenGeometryPadding
import dev.cannoli.ui.components.OsdController
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.OsdPill
import dev.cannoli.ui.components.OsdPillStyle
import dev.cannoli.ui.components.OsdPillText
import dev.cannoli.ui.components.OsdPanel
import dev.cannoli.ui.components.OsdPosition
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.cannoliColorsFromHex

/**
 * A persistent, input-transparent Compose overlay that renders Cannoli's OsdHost
 * on top of RetroArch's NativeActivity GL surface. Unlike the IGM overlay (a modal
 * Dialog that captures input), this window is non-focusable and non-touchable, so
 * it never intercepts gamepad input from the running game.
 */
class OsdOverlay(
    private val activity: Activity,
    private val fontFamily: FontFamily,
    private val colorHighlight: String? = null,
    private val colorText: String? = null,
    private val colorHighlightText: String? = null,
    private val colorAccent: String? = null,
    private val colorTitle: String? = null,
    private val portraitMarginPx: Int = 0,
    private val geometryWidthPct: Int = 100,
    private val geometryHeightPct: Int = 100,
    private val geometryXPct: Int = 0,
    private val geometryYPct: Int = 0,
) {
    val controller = OsdController()
    private val lifecycleOwner = IGMLifecycleOwner()
    private var view: ComposeView? = null
    private var added = false
    private val fastForward = mutableStateOf(false)
    private val showFps = mutableStateOf(false)
    private val rewinding = mutableStateOf(false)
    private val rewindAtEnd = mutableStateOf(false)
    private val showDebug = mutableStateOf(false)
    private val igmOpen = mutableStateOf(false)

    fun attach(savedInstanceState: Bundle?) {
        lifecycleOwner.performCreate(savedInstanceState)
        lifecycleOwner.performStart()
        lifecycleOwner.performResume()

        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { OsdContent() }
        }
        view = composeView

        // Defer until the activity window is attached so the token is available.
        activity.window.decorView.post {
            added = runCatching { activity.windowManager.addView(composeView, params()) }.isSuccess
        }
    }

    /**
     * Puts this window back at the top of the panel stack. Sibling panels are ordered by when they
     * were added, so a window added after this one covers the pill until it is added again.
     */
    fun raise() {
        val composeView = view ?: return
        if (!added) return
        added = runCatching {
            activity.windowManager.removeView(composeView)
            activity.windowManager.addView(composeView, params())
        }.isSuccess
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        // NO_LIMITS makes the overlay span the full screen (under the system
        // bars) from the start, so the pill doesn't shift when the game's
        // immersive mode settles.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        token = activity.window.decorView.windowToken
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun detach() {
        view?.let { runCatching { activity.windowManager.removeView(it) } }
        view = null
        added = false
        lifecycleOwner.performPause()
        lifecycleOwner.performStop()
        lifecycleOwner.performDestroy()
    }

    fun showMessage(message: String, position: OsdPosition = OsdPosition.BottomCenter) {
        controller.show(message, position)
    }

    /**
     * The prompt shown while a hold-style chord counts down, with the time left beside it.
     *
     * Not a toast: a toast says something happened and then ages out on its own clock, where this
     * has to track a deadline it does not own and vanish the moment the chord is let go. It carries
     * its own deadline for the same reason the fast forward pill does.
     */
    private class HoldPrompt(val label: String, val done: String, val deadlineMs: Long)

    private val holdPrompt = mutableStateOf<HoldPrompt?>(null)

    fun showHoldPrompt(message: String, done: String, holdMs: Int) {
        holdPrompt.value = HoldPrompt(message, done, SystemClock.elapsedRealtime() + holdMs)
    }

    fun clearHoldPrompt() {
        holdPrompt.value = null
    }

    /**
     * The two indicators that stay up rather than passing, sharing one pill as v1 drew them.
     *
     * A toast would announce that something changed and then leave nothing on screen to say it is
     * still changed, which for a mode you can sit in for minutes is the part that matters.
     */
    fun setFastForward(on: Boolean) {
        fastForward.value = on
    }

    fun setShowFps(on: Boolean) {
        showFps.value = on
    }

    fun setRewinding(on: Boolean) {
        rewinding.value = on
        // A fresh press has history again until RetroArch says otherwise.
        if (!on) rewindAtEnd.value = false
    }

    /**
     * RetroArch has run out of buffer, so the arrows say the rewind is going nowhere.
     *
     * Ignored unless a rewind is actually running. This is raised every frame the buffer stays
     * empty and travels the command queue, while the release that ends the rewind travels the
     * action queue and is drained first: without this, the last of those events landed after the
     * release had cleared the flag and left it stuck on for the rest of the session.
     */
    fun setRewindAtEnd() {
        if (rewinding.value) rewindAtEnd.value = true
    }

    /**
     * Hides the standing pill while the menu, or a guide, is up.
     *
     * This window is deliberately raised above the menu so toasts still land on top of it, which
     * would otherwise leave the pill sitting over a screen that is not the game it describes.
     * Transient messages are unaffected: those the menu raises itself.
     */
    fun setIgmOpen(open: Boolean) {
        igmOpen.value = open
        // A prompt counting down behind the menu has nothing left to complete it, since the action
        // it belongs to refuses to fire while the menu is up.
        if (open) holdPrompt.value = null
    }

    /** Polled while the counter is up, since nothing pushes a frame rate. */
    var fpsProvider: (() -> Float)? = null

    fun setShowDebug(on: Boolean) {
        showDebug.value = on
    }

    /** Polled while the panel is up, for the same reason the rate is. */
    var debugStatsProvider: (() -> List<Pair<String, String>>)? = null

    fun showAchievement(title: String) {
        controller.show("󰔸 $title", OsdPosition.BottomCenterLow)
    }

    /**
     * Fast forward and the frame rate, in one pill at the top right, as v1 drew them.
     *
     * U+25B6 twice for fast forward: wide content is what makes Radius.Pill read as a pill, where
     * one square icon glyph turns the same 50% radius into a circle. The bundled font covers that
     * codepoint, so no system or emoji font is consulted.
     */
    @Composable
    private fun BoxScope.StatusPill() {
        val ff = fastForward.value
        val rw = rewinding.value
        val fps = showFps.value
        // All of these describe the game, so none belongs over the menu or a guide.
        if (igmOpen.value || (!ff && !rw && !fps)) return
        val rate = remember { mutableStateOf(0f) }
        LaunchedEffect(fps) {
            while (fps) {
                rate.value = fpsProvider?.invoke() ?: 0f
                delay(FPS_SAMPLE_MS)
            }
        }
        val icon = when {
            // Only one of these runs at a time, since each is held.
            rw && rewindAtEnd.value -> CannoliIcons.RewindEnd.glyph
            rw -> CannoliIcons.Rewind.glyph
            ff -> CannoliIcons.FastForward.glyph
            else -> null
        }
        OsdPill(OsdPosition.TopEnd, OsdPillStyle.Icon) {
            icon?.let { OsdPillText(it, OsdPillStyle.Icon.fontSize) }
            if (fps) {
                if (icon != null) Spacer(Modifier.width(8.dp))
                // Its own item at reading size, centred beside the glyph rather than sharing a
                // baseline with it, which left the number sitting low against the icon.
                OsdPillText(String.format(Locale.US, "%.2f", rate.value), OsdPillStyle.Text.fontSize)
            }
        }
    }

    /**
     * Tenths still to go, rounded up so every value gets one tick.
     *
     * Rounding to nearest gave 0.1 two ticks, since 0.15 and 0.05 both render as one tenth, and the
     * number visibly stalled there at the very moment the user is watching it hardest.
     */
    private fun tenthsLeft(deadlineMs: Long): Long {
        val left = deadlineMs - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else (left + HOLD_TICK_MS - 1) / HOLD_TICK_MS
    }

    /**
     * Counts down in tenths, which the bundled font renders without the pill twitching: every digit
     * in it is 640 units wide, so the text cannot change width as the number falls.
     *
     * Clears itself at zero. The action firing is what ends the hold, and that already leaves the
     * game, so nothing else has to come along and take this down.
     */
    @Composable
    private fun BoxScope.HoldPill() {
        val prompt = holdPrompt.value ?: return
        val tenths = remember(prompt) { mutableStateOf(tenthsLeft(prompt.deadlineMs)) }
        LaunchedEffect(prompt) {
            while (true) {
                val left = prompt.deadlineMs - SystemClock.elapsedRealtime()
                tenths.value = tenthsLeft(prompt.deadlineMs)
                if (left <= 0) break
                // To the next tenth of the deadline rather than a flat 100ms from wherever this
                // woke. A fixed delay drifts off the deadline, so the last step ran late and the
                // pill outlived the press it was counting.
                delay((left - 1) % HOLD_TICK_MS + 1)
            }
        }
        // Held past zero rather than taken down. The action cannot land the instant the countdown
        // ends: it crosses two queues and a thread, and RetroArch writes the auto save state before
        // it will shut down. Hiding here would leave that stretch blank, which read as a hang.
        if (tenths.value <= 0) {
            OsdPill(prompt.done, OsdPosition.BottomCenter)
            return
        }
        OsdPill(
            "${prompt.label}  ${tenths.value / 10}.${tenths.value % 10}",
            OsdPosition.BottomCenter,
        )
    }

    /**
     * The figures RetroArch would have drawn, drawn here instead.
     *
     * Opposite corner to the status pill so the two never sit on top of each other, and sampled on
     * the same half second: these move every frame, and a panel that redrew with them would be
     * unreadable as well as wasteful.
     */
    @Composable
    private fun BoxScope.DebugPanel() {
        if (igmOpen.value || !showDebug.value) return
        val rows = remember { mutableStateOf(emptyList<Pair<String, String>>()) }
        LaunchedEffect(showDebug.value) {
            while (showDebug.value) {
                rows.value = debugStatsProvider?.invoke().orEmpty()
                delay(FPS_SAMPLE_MS)
            }
        }
        OsdPanel(rows.value, OsdPosition.TopStart)
    }

    @Composable
    private fun OsdContent() {
        val colors = cannoliColorsFromHex(
            colorHighlight, colorText, colorHighlightText, colorAccent, colorTitle,
        )
        CannoliTheme(fontFamily = fontFamily) {
            CompositionLocalProvider(LocalCannoliColors provides colors) {
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current
                // Unlike the IGM, nothing else in this content reads state that would trigger a
                // later recomposition, so without hoisting the laid-out size here the dp fallback
                // below would be permanent, not just a first-frame default.
                val surfaceSize = remember { mutableStateOf(IntSize.Zero) }
                val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val regionPadding = computeScreenGeometryPadding(
                    surfaceWidthPx = surfaceSize.value.width,
                    surfaceHeightPx = surfaceSize.value.height,
                    surfaceWidthDp = configuration.screenWidthDp,
                    surfaceHeightDp = configuration.screenHeightDp,
                    widthPct = geometryWidthPct,
                    heightPct = geometryHeightPct,
                    xPct = geometryXPct,
                    yPct = geometryYPct,
                    portraitMarginPx = portraitMarginPx,
                    portrait = portrait,
                    density = density,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { surfaceSize.value = it }
                        .padding(regionPadding)
                ) {
                    OsdHost(controller)
                    StatusPill()
                    HoldPill()
                    DebugPanel()
                }
            }
        }
    }

    private companion object {
        // As v1 sampled it. The native side already averages over the same window, so reading more
        // often would redraw the same figure.
        const val FPS_SAMPLE_MS = 500L
        const val HOLD_TICK_MS = 100L
    }
}
