package com.retroarch.browser.retroactivity

import android.app.Activity
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.cannoli.ui.computeScreenGeometryPadding
import dev.cannoli.ui.components.OsdController
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.OsdPosition
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.hexToColor

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

    fun showAchievement(title: String) {
        controller.show("󰔸 $title", OsdPosition.BottomCenterLow)
    }

    @Composable
    private fun OsdContent() {
        val colors = CannoliColors(
            highlight = colorHighlight?.let { hexToColor(it) } ?: Color.White,
            text = colorText?.let { hexToColor(it) } ?: Color.White,
            highlightText = colorHighlightText?.let { hexToColor(it) } ?: Color.Black,
            accent = colorAccent?.let { hexToColor(it) } ?: Color.White,
            title = colorTitle?.let { hexToColor(it) } ?: Color.White
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
                ) { OsdHost(controller) }
            }
        }
    }
}
