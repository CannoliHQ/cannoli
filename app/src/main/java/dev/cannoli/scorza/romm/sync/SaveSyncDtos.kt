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
    /**
     * The ROMs this request is about, so the server answers for them instead of the account's
     * whole save library: a handheld holding thirty games against a three thousand game library
     * was being handed operations for all three thousand.
     *
     * Null is no scope and is what an older server sees, since it drops a field it does not know
     * and answers exactly as it does today. An empty list is not the same thing, it is an explicit
     * empty scope, so this is left null rather than sent empty. The server caps a scope at 500 and
     * rejects a longer one outright, so a library past that is sent unscoped.
     */
    @SerialName("rom_ids") val romIds: List<Int>? = null,
)

/** The server's cap on a rom_ids scope. Past it the request is a 422, so it goes unscoped. */
internal const val MAX_ROM_IDS_PER_QUERY = 500

/** Null rather than empty, because an empty list asks the server for an empty scope. */
internal fun romIdScope(ids: Collection<Int>): List<Int>? =
    ids.distinct().takeIf { it.isNotEmpty() && it.size <= MAX_ROM_IDS_PER_QUERY }

/**
 * What the server decided for one save.
 *
 * [Unknown] is not [NoOp]: a verdict this build does not recognise means nothing has been settled,
 * and treating it as "nothing to do" launches the game against whatever is on disk.
 */
enum class SyncAction {
    Download, Upload, Conflict, NoOp, Unknown;

    companion object {
        fun of(wire: String?): SyncAction = when (wire) {
            "download" -> Download
            "upload" -> Upload
            "conflict" -> Conflict
            "no_op" -> NoOp
            else -> Unknown
        }
    }
}

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
) {
    val verdict: SyncAction get() = SyncAction.of(action)
}

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
    /**
     * What the server remembers about each device that has synced this save. Cannoli writes this
     * every time it confirms a download and, until now, never read it back, which left a local
     * anchor as the only memory it had of its own sync history.
     */
    @SerialName("device_syncs") val deviceSyncs: List<DeviceSyncDto> = emptyList(),
) {
    /** This device's own record, which is the one that says what we last had. */
    fun syncFor(deviceId: String): DeviceSyncDto? = deviceSyncs.firstOrNull { it.deviceId == deviceId }

    /** A save the user has told this device to leave alone. RomM's negotiate already skips these. */
    fun isUntrackedOn(deviceId: String): Boolean = syncFor(deviceId)?.isUntracked == true

    /** The device that last wrote this save, named for a person choosing between two saves. */
    fun originDeviceName(): String? =
        originDeviceId?.let { id -> deviceSyncs.firstOrNull { it.deviceId == id }?.deviceName }
}

@Serializable
data class DeviceSyncDto(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("is_untracked") val isUntracked: Boolean = false,
    @SerialName("is_current") val isCurrent: Boolean = false,
)

@Serializable
data class DeleteSavesPayload(val saves: List<Int>)

@Serializable
data class ConfirmDownloadPayload(@SerialName("device_id") val deviceId: String)
