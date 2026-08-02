package dev.cannoli.scorza.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

object DeviceType {
    // Not FEATURE_LEANBACK: that only means the TV UI and apps are present, and TV-derived handheld
    // ROMs declare it while running as ordinary handhelds (GammaOS on the Miyoo Flip is a LineageOS
    // TV GSI, and reports UI_MODE_TYPE_NORMAL). The ui mode type is what actually separates them.
    // Use leanback only to ask whether the TV UI/apps exist, never to ask whether this is a TV.
    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    // "AVD" here means an Android Virtual Device (the Studio emulator we run the app on), never a
    // game emulator like RetroArch: "emulator" is already taken by that sense across this
    // codebase. Build fields are parameters so this stays a pure function under unit test.
    //
    // Every clause is a positive emulator marker. Deliberately absent: "generic" and "unknown"
    // fingerprint prefixes. Build.getString returns "unknown" for any unset property and
    // Build.FINGERPRINT is composed from ro.product.brand when ro.build.fingerprint is empty, so
    // those prefixes flag any handheld with sloppy build props (the machines running hacked ROMs)
    // rather than an emulator.
    fun isAvd(
        fingerprint: String = Build.FINGERPRINT ?: "",
        model: String = Build.MODEL ?: "",
        hardware: String = Build.HARDWARE ?: "",
        product: String = Build.PRODUCT ?: "",
    ): Boolean =
        hardware == "goldfish" ||
            hardware == "ranchu" ||
            hardware.contains("emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            model.startsWith("sdk_gphone", ignoreCase = true) ||
            fingerprint.contains("/sdk_gphone", ignoreCase = true) ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            product.startsWith("sdk_", ignoreCase = true)
}
