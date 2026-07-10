package dev.cannoli.scorza.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.computeScreenGeometryRect

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
    val portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
    val rect = computeScreenGeometryRect(
        config.screenWidthDp, config.screenHeightDp,
        insets.geometryWidthPct, insets.geometryHeightPct, insets.geometryXPct, insets.geometryYPct,
    )
    val bottomMargin = if (portrait) insets.portraitMarginPx else 0
    val density = LocalDensity.current
    return with(density) {
        PaddingValues(
            start = rect.x.dp,
            top = rect.y.dp,
            end = (config.screenWidthDp - rect.x - rect.w).coerceAtLeast(0).dp,
            bottom = (config.screenHeightDp - rect.y - rect.h).coerceAtLeast(0).dp + bottomMargin.toDp(),
        )
    }
}
