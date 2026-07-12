package dev.cannoli.scorza.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

object DeviceType {
    // Not FEATURE_LEANBACK: that only means the TV UI and apps are present, and TV-derived handheld
    // ROMs declare it while running as ordinary handhelds (GammaOS on the Miyoo Flip is a LineageOS
    // TV GSI, and reports UI_MODE_TYPE_NORMAL). The ui mode type is what actually separates them.
    // Use leanback only to ask whether the TV UI/apps exist, never to ask whether this is a TV.
    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
