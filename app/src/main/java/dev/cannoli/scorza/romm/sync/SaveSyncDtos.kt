package dev.cannoli.scorza.romm.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRegisterPayload(
    val name: String,
    val platform: String = "android",
    val client: String = "cannoli",
    @SerialName("client_version") val clientVersion: String,
    @SerialName("sync_mode") val syncMode: String = "api",
)

@Serializable
data class DeviceRegisterResponse(
    @SerialName("device_id") val deviceId: String,
    val name: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ClientSaveState(
    @SerialName("rom_id") val romId: Int,
    @SerialName("file_name") val fileName: String,
    val slot: String? = null,
    val emulator: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
)

@Serializable
data class SyncNegotiatePayload(
    @SerialName("device_id") val deviceId: String,
    val saves: List<ClientSaveState>,
)

@Serializable
data class SyncOperationDto(
    val action: String,
    @SerialName("rom_id") val romId: Int,
    @SerialName("save_id") val saveId: Int? = null,
    @SerialName("file_name") val fileName: String,
    val slot: String? = null,
    val emulator: String? = null,
    val reason: String = "",
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
    @SerialName("server_content_hash") val serverContentHash: String? = null,
)

@Serializable
data class SyncNegotiateResponse(
    @SerialName("session_id") val sessionId: Int,
    val operations: List<SyncOperationDto> = emptyList(),
    @SerialName("total_upload") val totalUpload: Int = 0,
    @SerialName("total_download") val totalDownload: Int = 0,
    @SerialName("total_conflict") val totalConflict: Int = 0,
    @SerialName("total_no_op") val totalNoOp: Int = 0,
)

@Serializable
data class SyncCompletePayload(
    @SerialName("operations_completed") val operationsCompleted: Int = 0,
    @SerialName("operations_failed") val operationsFailed: Int = 0,
)

@Serializable
data class RommSaveDto(
    val id: Int,
    @SerialName("rom_id") val romId: Int = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val slot: String? = null,
    val emulator: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("origin_device_id") val originDeviceId: String? = null,
    @SerialName("download_path") val downloadPath: String? = null,
)

@Serializable
data class DeleteSavesPayload(val saves: List<Int>)

@Serializable
data class ConfirmDownloadPayload(@SerialName("device_id") val deviceId: String)
