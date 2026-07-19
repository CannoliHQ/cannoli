package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

internal data class DisplayCandidate(
    val id: Int,
    val flags: Int,
    val isValid: Boolean,
    val widthPixels: Int,
    val heightPixels: Int,
    val physicalAreaSquareInches: Double? = null,
)

internal data class DisplaySizeRoute(
    val launcherDisplayId: Int,
    val gameDisplayId: Int,
)

internal enum class LauncherDisplayTransition {
    SYNC_IN_PLACE,
    COVER_GAME_DISPLAY_THEN_MOVE,
    MOVE_THEN_SYNC_ON_DESTINATION,
}

internal fun launcherDisplayTransition(
    currentDisplayId: Int,
    targetDisplayId: Int?,
    gameDisplayId: Int?,
): LauncherDisplayTransition = when {
    targetDisplayId == null || targetDisplayId == currentDisplayId ->
        LauncherDisplayTransition.SYNC_IN_PLACE
    gameDisplayId != null -> LauncherDisplayTransition.COVER_GAME_DISPLAY_THEN_MOVE
    else -> LauncherDisplayTransition.MOVE_THEN_SYNC_ON_DESTINATION
}

internal fun isDualScreenRoutingEnabled(
    experimentalFeatures: Boolean,
    dualScreenLaunching: Boolean,
): Boolean = experimentalFeatures && dualScreenLaunching

internal fun physicalAreaSquareInches(
    widthPixels: Int,
    heightPixels: Int,
    xdpi: Float,
    ydpi: Float,
): Double? {
    if (widthPixels <= 0 || heightPixels <= 0) return null
    if (!xdpi.isFinite() || !ydpi.isFinite() || xdpi < 30f || ydpi < 30f) return null
    return (widthPixels.toDouble() / xdpi) * (heightPixels.toDouble() / ydpi)
}

internal fun selectDisplaySizeRoute(displays: List<DisplayCandidate>): DisplaySizeRoute? {
    val eligible = displays.filter {
        it.isValid &&
            it.flags and Display.FLAG_PRIVATE == 0
    }
    if (eligible.size < 2) return null

    val allHavePhysicalArea = eligible.all { it.physicalAreaSquareInches != null }
    val ranked = eligible.sortedWith(
        compareBy<DisplayCandidate> {
            if (allHavePhysicalArea) {
                requireNotNull(it.physicalAreaSquareInches)
            } else {
                it.widthPixels.toDouble() * it.heightPixels
            }
        }.thenBy { it.widthPixels.toLong() * it.heightPixels }
            // Equal-sized displays retain the old non-default launcher/default game behavior.
            .thenBy { if (it.id == Display.DEFAULT_DISPLAY) 1 else 0 }
    )
    return DisplaySizeRoute(
        launcherDisplayId = ranked.first().id,
        gameDisplayId = ranked.last().id,
    )
}

fun Context.noAnimationActivityOptions(launchDisplayId: Int? = null): Bundle =
    ActivityOptions.makeCustomAnimation(this, 0, 0).apply {
        launchDisplayId?.let(::setLaunchDisplayId)
    }.toBundle()

@Singleton
class ActivityDisplayRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val displayManager: DisplayManager =
        context.getSystemService(DisplayManager::class.java)

    private val isRoutingEnabled: Boolean
        get() = isDualScreenRoutingEnabled(
            experimentalFeatures = settings.experimentalFeatures,
            dualScreenLaunching = settings.dualScreenLaunching,
        )

    val isDualScreenAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
        ) && displaySizeRoute() != null

    @Suppress("DEPRECATION")
    private fun displayCandidates(): List<DisplayCandidate> =
        displayManager.displays.map { display ->
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            DisplayCandidate(
                id = display.displayId,
                flags = display.flags,
                isValid = display.isValid,
                widthPixels = metrics.widthPixels,
                heightPixels = metrics.heightPixels,
                physicalAreaSquareInches = physicalAreaSquareInches(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.xdpi,
                    metrics.ydpi,
                ),
            )
        }

    private fun displaySizeRoute(): DisplaySizeRoute? =
        selectDisplaySizeRoute(displayCandidates())

    fun preferredLauncherDisplayId(forcePrimaryWhenDisabled: Boolean = false): Int? =
        if (isRoutingEnabled) {
            displaySizeRoute()?.launcherDisplayId
        } else {
            Display.DEFAULT_DISPLAY.takeIf { forcePrimaryWhenDisabled }
        }

    fun gameLaunchDisplayId(): Int? =
        if (isRoutingEnabled) {
            displaySizeRoute()?.gameDisplayId ?: Display.DEFAULT_DISPLAY
        } else {
            null
        }

    fun diagnosticSummary(): String {
        val candidates = displayCandidates()
        val displaysById = displayManager.displays.associateBy { it.displayId }
        val displays = candidates.joinToString(prefix = "[", postfix = "]") { candidate ->
            val display = displaysById[candidate.id]
            "id=${candidate.id},name=${display?.name},state=${display?.state}," +
                "flags=0x${candidate.flags.toString(16)}," +
                "pixels=${candidate.widthPixels}x${candidate.heightPixels}," +
                "areaIn2=${candidate.physicalAreaSquareInches ?: "unknown"}"
        }
        val route = selectDisplaySizeRoute(candidates)
        return "experimental=${settings.experimentalFeatures},dual=${settings.dualScreenLaunching}," +
            "enabled=$isRoutingEnabled,default=${Display.DEFAULT_DISPLAY}," +
            "launcher=${route?.launcherDisplayId},game=${route?.gameDisplayId},displays=$displays"
    }
}
