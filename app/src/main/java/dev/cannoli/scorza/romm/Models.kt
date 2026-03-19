package dev.cannoli.scorza.romm

data class HeartbeatResponse(val version: String)

enum class ConnectionResult { OK, UNAUTHORIZED, FORBIDDEN, SERVER_ERROR, ERROR }

data class TokenExchangeResponse(
    val rawToken: String,
    val name: String,
    val expiresAt: String
)

data class RommPlatform(
    val id: Int,
    val slug: String,
    val fsSlug: String,
    val name: String,
    val customName: String,
    val romCount: Int,
    val firmwareCount: Int
) {
    val displayName: String get() = customName.ifEmpty { name }
}

data class PaginatedRoms(
    val items: List<RommRom>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class GetRomsQuery(
    val platformId: Int = 0,
    val collectionId: Int = 0,
    val search: String = "",
    val limit: Int = 0,
    val offset: Int = 0,
    val orderBy: String = "",
    val orderDir: String = ""
)

data class RommRom(
    val id: Int,
    val platformId: Int,
    val platformFsSlug: String,
    val platformDisplayName: String,
    val name: String,
    val fsName: String,
    val fsNameNoExt: String,
    val fsExtension: String,
    val fsSizeBytes: Long,
    val summary: String,
    val pathCoverSmall: String,
    val pathCoverLarge: String,
    val urlCover: String,
    val hasMultipleFiles: Boolean,
    val files: List<RomFile>
) {
    fun coverUrl(baseUrl: String): String = when {
        pathCoverSmall.isNotEmpty() -> "$baseUrl$pathCoverSmall"
        pathCoverLarge.isNotEmpty() -> "$baseUrl$pathCoverLarge"
        urlCover.isNotEmpty() -> urlCover
        else -> ""
    }
}

data class RomFile(
    val id: Int,
    val fileName: String,
    val fileSizeBytes: Long
)

data class RommSave(
    val id: Int,
    val romId: Int,
    val fileName: String,
    val fileNameNoExt: String,
    val fileExtension: String,
    val fileSizeBytes: Long,
    val downloadPath: String,
    val emulator: String,
    val updatedAt: String,
    val slot: String?,
    val deviceSyncs: List<DeviceSaveSync>
) {
    fun isDeviceSynced(deviceId: String): Boolean =
        deviceSyncs.any { it.deviceId == deviceId && it.isCurrent }
}

data class DeviceSaveSync(
    val deviceId: String,
    val deviceName: String,
    val lastSyncedAt: String,
    val isCurrent: Boolean
)

data class RommDevice(
    val id: String,
    val name: String
)

data class RommFirmware(
    val id: Int,
    val fileName: String,
    val fileSizeBytes: Long,
    val md5Hash: String,
    val downloadUrl: String
)

class ApiException(val statusCode: Int, val body: String) :
    Exception("API error $statusCode: $body")
