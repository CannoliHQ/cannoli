package dev.cannoli.igm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Meeting point for the second-display guide overlay. The service itself lives in the app module,
 * which the in-game menu's host cannot depend on, so both sides build the same intent from here and
 * a key can never drift between producer and consumer.
 */
object GuideOverlayContract {
    const val SERVICE_CLASS = "dev.cannoli.scorza.launcher.GuideOverlayService"

    const val EXTRA_DISPLAY_ID = "displayId"
    const val EXTRA_FILE_PATH = "filePath"
    const val EXTRA_GUIDE_TYPE = "guideType"
    const val EXTRA_PLATFORM_TAG = "platformTag"
    const val EXTRA_ROM_BASE_NAME = "romBaseName"
    const val EXTRA_PAGE = "page"
    const val EXTRA_SCROLL_Y = "scrollY"
    const val EXTRA_SCROLL_X = "scrollX"
    const val EXTRA_TEXT_ZOOM = "textZoom"
    const val EXTRA_PAGE_COUNT = "pageCount"

    // SYSTEM_ALERT_WINDOW is not granted by a runtime request dialog, so an ungranted install has
    // to fall back to the main screen rather than showing nothing.
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun start(
        context: Context,
        displayId: Int,
        filePath: String,
        guideType: GuideType,
        platformTag: String,
        romBaseName: String,
        page: Int,
        scrollY: Int,
        scrollX: Int,
        textZoom: Int,
        pageCount: Int,
    ): Boolean = runCatching {
        context.startService(
            intent(
                context = context,
                displayId = displayId,
                filePath = filePath,
                guideType = guideType,
                platformTag = platformTag,
                romBaseName = romBaseName,
                page = page,
                scrollY = scrollY,
                scrollX = scrollX,
                textZoom = textZoom,
                pageCount = pageCount,
            )
        )
    }.getOrNull() != null

    private fun intent(
        context: Context,
        displayId: Int,
        filePath: String,
        guideType: GuideType,
        platformTag: String,
        romBaseName: String,
        page: Int,
        scrollY: Int,
        scrollX: Int,
        textZoom: Int,
        pageCount: Int,
    ): Intent = Intent().apply {
        // Named, not referenced: the emulator process cannot see the service class at compile time.
        // Every caller is the same app and uid, so an explicit component starts it even though the
        // service is not exported.
        component = ComponentName(context.packageName, SERVICE_CLASS)
        putExtra(EXTRA_DISPLAY_ID, displayId)
        putExtra(EXTRA_FILE_PATH, filePath)
        putExtra(EXTRA_GUIDE_TYPE, guideType.name)
        putExtra(EXTRA_PLATFORM_TAG, platformTag)
        putExtra(EXTRA_ROM_BASE_NAME, romBaseName)
        putExtra(EXTRA_PAGE, page)
        putExtra(EXTRA_SCROLL_Y, scrollY)
        putExtra(EXTRA_SCROLL_X, scrollX)
        putExtra(EXTRA_TEXT_ZOOM, textZoom)
        putExtra(EXTRA_PAGE_COUNT, pageCount)
    }
}
