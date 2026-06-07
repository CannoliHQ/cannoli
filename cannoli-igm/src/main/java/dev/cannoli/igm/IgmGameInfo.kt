package dev.cannoli.igm

data class IgmGameInfo(
    val coreName: String = "",
    val romPath: String = "",
    val extractedPath: String = "",
    val savePath: String = "",
    val rendererName: String = "",
    val raStatus: String = "",
    val raGameId: String = "",
    val raDetection: String = "",
    val rootPrefix: String = "",
)
