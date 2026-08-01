package dev.cannoli.scorza.model

import java.io.File

data class App(
    val id: Long,
    val type: AppType,
    val displayName: String,
    val packageName: String,
    val lastPlayedAt: Long? = null,
    val artFile: File? = null,
)

enum class AppType { TOOL, PORT }

val AppType.artTag: String get() = if (this == AppType.TOOL) "TOOLS" else "PORTS"
