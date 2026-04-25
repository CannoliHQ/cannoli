package dev.cannoli.scorza.model

import java.io.File

fun Rom.toLaunchGame(): Game = Game(
    file = path,
    displayName = displayName,
    platformTag = platformTag,
    artFile = artFile,
    launchTarget = launchTarget,
    discFiles = discFiles,
)

fun App.toLaunchGame(): Game = Game(
    file = File("/apps/${type.name}/${packageName}"),
    displayName = displayName,
    platformTag = if (type == AppType.TOOL) "tools" else "ports",
    launchTarget = LaunchTarget.ApkLaunch(packageName),
)

fun ListItem.toLaunchGame(): Game? = when (this) {
    is ListItem.RomItem -> rom.toLaunchGame()
    is ListItem.AppItem -> app.toLaunchGame()
    else -> null
}
