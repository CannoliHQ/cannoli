package dev.cannoli.scorza.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

data class PortraitMarginState(val marginPx: Int = 0)

val LocalPortraitMargin = compositionLocalOf { PortraitMarginState() }

@Composable
@ReadOnlyComposable
fun effectivePortraitMarginDp(): Dp {
    val state = LocalPortraitMargin.current
    if (state.marginPx <= 0) return Dp(0f)
    val config = LocalConfiguration.current
    if (config.orientation != Configuration.ORIENTATION_PORTRAIT) return Dp(0f)
    val density = LocalDensity.current
    return with(density) { state.marginPx.toDp() }
}
