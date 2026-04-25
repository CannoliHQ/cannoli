package dev.cannoli.scorza.model

data class App(
    val id: Long,
    val type: AppType,
    val displayName: String,
    val packageName: String,
    val lastPlayedAt: Long? = null,
)

enum class AppType { TOOL, PORT }
