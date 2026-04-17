package dev.cannoli.ui.theme

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.unit.sp

lateinit var MPlus1Code: FontFamily
    private set

lateinit var BPReplay: FontFamily
    private set

fun initFonts(assets: AssetManager) {
    val mplus = Typeface.createFromAsset(assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
    MPlus1Code = FontFamily(ComposeTypeface(mplus))
    val bp = Typeface.createFromAsset(assets, "fonts/BPreplayBold-unhinted.otf")
    BPReplay = FontFamily(ComposeTypeface(bp))
}

fun buildTypography(fontFamily: FontFamily = MPlus1Code): Typography {
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
