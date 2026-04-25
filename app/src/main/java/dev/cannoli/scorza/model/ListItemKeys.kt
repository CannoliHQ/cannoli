package dev.cannoli.scorza.model

fun ListItem.recentKey(): String? = when (this) {
    is ListItem.RomItem -> rom.path.absolutePath
    is ListItem.AppItem -> "/apps/${app.type.name}/${app.packageName}"
    else -> null
}
