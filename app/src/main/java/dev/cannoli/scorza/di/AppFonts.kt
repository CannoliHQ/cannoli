package dev.cannoli.scorza.di

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppFonts @Inject constructor(@ApplicationContext context: Context) {
    val mplus1Code: FontFamily
    val bpReplay: FontFamily

    init {
        val mplus = Typeface.createFromAsset(context.assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
        mplus1Code = FontFamily(ComposeTypeface(mplus))
        val bp = Typeface.createFromAsset(context.assets, "fonts/BPreplayBold-unhinted.otf")
        bpReplay = FontFamily(ComposeTypeface(bp))
    }
}
