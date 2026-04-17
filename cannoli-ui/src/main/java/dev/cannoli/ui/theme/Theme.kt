package dev.cannoli.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily

private val CannoliColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    background = Black,
    surface = Black,
    onBackground = White,
    onSurface = White,
    surfaceVariant = DarkGray,
    onSurfaceVariant = GrayText
)

@Composable
fun CannoliTheme(fontFamily: FontFamily = MPlus1Code, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCannoliFont provides fontFamily) {
        MaterialTheme(
            colorScheme = CannoliColorScheme,
            typography = buildTypography(fontFamily),
            content = content
        )
    }
}
