package dev.cannoli.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class CannoliTypography(
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val labelSmall: TextStyle
)

fun buildCannoliTypography(baseSizeSp: Int = 22, fontFamily: FontFamily = FontFamily.Default): CannoliTypography {
    val scale = baseSizeSp / 22f
    return CannoliTypography(
        titleLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Black, fontSize = (28 * scale).sp, lineHeight = (36 * scale).sp),
        titleMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Black, fontSize = (22 * scale).sp, lineHeight = (32 * scale).sp),
        bodyLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Black, fontSize = (22 * scale).sp, lineHeight = (32 * scale).sp),
        bodyMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = (16 * scale).sp, lineHeight = (22 * scale).sp),
        labelSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = (14 * scale).sp, lineHeight = (18 * scale).sp)
    )
}

val LocalCannoliTypography = staticCompositionLocalOf {
    buildCannoliTypography()
}

fun buildTypography(fontFamily: FontFamily = FontFamily.Default): Typography {
    return Typography(
        headlineLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            color = Color.White
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            color = Color.White
        ),
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            lineHeight = 32.sp,
            color = Color.White
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color.White
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color.White
        )
    )
}
