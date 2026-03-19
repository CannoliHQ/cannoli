package dev.cannoli.scorza.romm

object Endpoints {
    const val HEARTBEAT = "/api/heartbeat"
    const val LOGIN = "/api/login"
    const val CONFIG = "/api/config"

    const val PLATFORMS = "/api/platforms"
    fun platformById(id: Int) = "/api/platforms/$id"

    const val ROMS = "/api/roms"
    const val ROMS_DOWNLOAD = "/api/roms/download"
    fun romById(id: Int) = "/api/roms/$id"

    const val SAVES = "/api/saves"
    fun saveById(id: Int) = "/api/saves/$id"
    fun saveContent(id: Int) = "/api/saves/$id/content"
    fun saveDownloaded(id: Int) = "/api/saves/$id/downloaded"

    const val DEVICES = "/api/devices"
    fun deviceById(id: String) = "/api/devices/$id"

    const val FIRMWARE = "/api/firmware"

    const val TOKEN_EXCHANGE = "/api/client-tokens/exchange"
    const val CURRENT_USER = "/api/users/me"
}
