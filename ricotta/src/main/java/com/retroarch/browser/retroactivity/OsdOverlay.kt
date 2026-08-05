package com.retroarch.browser.retroactivity

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
) {
    val controller = OsdController()
    private val lifecycleOwner = IGMLifecycleOwner()
    private var view: ComposeView? = null

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
            val params = WindowManager.LayoutParams(
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
            runCatching { activity.windowManager.addView(composeView, params) }
        }
    }

    fun detach() {
        view?.let { runCatching { activity.windowManager.removeView(it) } }
        view = null
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
                Box(Modifier.fillMaxSize()) { OsdHost(controller) }
            }
        }
    }
}
