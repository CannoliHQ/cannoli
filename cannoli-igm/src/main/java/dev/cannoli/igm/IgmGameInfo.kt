package dev.cannoli.igm

data class IgmGameInfo(
    val coreName: String = "",
    // The source ROM path (what the launcher's GameInfo calls originalRomPath).
    val romPath: String = "",
    // The post-extraction path when the ROM was extracted from an archive; empty otherwise.
    // (The launcher's GameInfo.romPath maps here, not to romPath above.)
    val extractedPath: String = "",
    // Empty string = hide the row (host maps a null source value to "").
    val savePath: String = "",
    val rendererName: String = "",
    val raStatus: String = "",
    val raGameId: String = "",
    val raDetection: String = "",
    // Display prefix stripped from paths before showing them.
    val rootPrefix: String = "",
)
