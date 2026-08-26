package dev.cannoli.scorza.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import dev.cannoli.ui.computeScreenGeometryPadding

data class ViewportInsetsPx(
    val geometryWidthPct: Int = 100,
    val geometryHeightPct: Int = 100,
    val geometryXPct: Int = 0,
    val geometryYPct: Int = 0,
    val portraitMarginPx: Int = 0,
)

val LocalViewportInsets = compositionLocalOf { ViewportInsetsPx() }

@Composable
@ReadOnlyComposable
fun effectiveViewportPadding(): PaddingValues {
    val insets = LocalViewportInsets.current
    val config = LocalConfiguration.current
    // The launcher never draws over the game, so it has no reason to measure the surface and stays
    // on dp. Passing no pixel size selects the shared function's dp branch, which is exactly what
    // this file used to reimplement: the in-game overlays pass real pixels and get the tighter
    // region that matches the viewport.
    return computeScreenGeometryPadding(
        surfaceWidthPx = 0,
        surfaceHeightPx = 0,
        surfaceWidthDp = config.screenWidthDp,
        surfaceHeightDp = config.screenHeightDp,
        widthPct = insets.geometryWidthPct,
        heightPct = insets.geometryHeightPct,
        xPct = insets.geometryXPct,
        yPct = insets.geometryYPct,
        portraitMarginPx = insets.portraitMarginPx,
        portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT,
        density = LocalDensity.current,
    )
}
