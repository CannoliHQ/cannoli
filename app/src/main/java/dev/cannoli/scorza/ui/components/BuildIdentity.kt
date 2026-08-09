package dev.cannoli.scorza.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BuildIdentityLines(val version: String, val detail: String?)

private const val RELEASE_SEPARATOR = "  •  "

// The release date names when the build was cut, so it has to read the same everywhere. Deriving
// it in the viewer's zone would slide a near-midnight build onto a different day per device.
private val BUILD_ZONE: TimeZone = TimeZone.getTimeZone("UTC")

private fun format(pattern: String, millis: Long, zone: TimeZone): String =
    SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(millis))

fun buildIdentityLines(
    debug: Boolean,
    versionName: String,
    hash: String,
    dirty: Boolean,
    buildTimeMillis: Long,
    debugLabel: String,
    deviceZone: TimeZone = TimeZone.getDefault(),
): BuildIdentityLines {
    if (!debug) {
        val date = format("yyyy-MM-dd", buildTimeMillis, BUILD_ZONE)
        return BuildIdentityLines(
            version = listOf("v$versionName", date, hash).joinToString(RELEASE_SEPARATOR),
            detail = null,
        )
    }
    val sha = if (dirty) "$hash-dirty" else hash
    return BuildIdentityLines(
        version = debugLabel,
        detail = "$sha${RELEASE_SEPARATOR}${format("yyyy-MM-dd HH:mm", buildTimeMillis, deviceZone)}",
    )
}
