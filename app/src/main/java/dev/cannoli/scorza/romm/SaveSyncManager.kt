package dev.cannoli.scorza.romm

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class SyncStatus { IDLE, SYNCING, UP_TO_DATE, CONFLICT, ERROR }

data class SyncState(
    val status: SyncStatus = SyncStatus.IDLE,
    val message: String = ""
)

class SaveSyncManager(
    private val cannoliRoot: File,
    private val client: RommClient,
    private val deviceId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val savesDir = File(cannoliRoot, "Saves")
    private val statesDir = File(cannoliRoot, "Save States")

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    private var fileObserver: FileObserver? = null
    private val pendingChanges = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val saveExtensions = setOf(
        "srm", "sav", "dsv", "mcr", "mcd", "brm", "eep", "sra", "fla", "mpk", "nv"
    )

    fun start() {
        startWatching()
    }

    fun stop() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    fun syncOnLaunch(platformTag: String, romName: String, onConflict: (String) -> Unit) {
        scope.launch {
            try {
                _syncState.value = SyncState(SyncStatus.SYNCING, "Checking saves...")
                val romId = findRomId(platformTag, romName) ?: run {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    return@launch
                }
                val remoteSaves = client.getSaves(romId, deviceId)
                if (remoteSaves.isEmpty()) {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    return@launch
                }

                val localSaveFile = findLocalSave(platformTag, romName)
                for (remote in remoteSaves) {
                    if (remote.isDeviceSynced(deviceId)) continue

                    if (localSaveFile != null && localSaveFile.exists()) {
                        val localModified = localSaveFile.lastModified()
                        val remoteTime = parseIsoTime(remote.updatedAt)
                        if (remoteTime > localModified + 1000) {
                            _syncState.value = SyncState(SyncStatus.CONFLICT, "Remote save is newer")
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                onConflict(remote.fileName)
                            }
                            return@launch
                        }
                    } else {
                        downloadSave(remote, platformTag, romName)
                    }
                }
                _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
            } catch (e: Exception) {
                _syncState.value = SyncState(SyncStatus.ERROR, e.message ?: "Sync failed")
            }
        }
    }

    fun syncOnQuit(platformTag: String, romName: String) {
        scope.launch {
            try {
                _syncState.value = SyncState(SyncStatus.SYNCING, "Uploading save...")
                val localSaveFile = findLocalSave(platformTag, romName) ?: run {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    return@launch
                }
                if (!localSaveFile.exists()) {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    return@launch
                }

                val romId = findRomId(platformTag, romName) ?: run {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    return@launch
                }

                val remoteSaves = client.getSaves(romId, deviceId)
                val existing = remoteSaves.firstOrNull { it.fileName == localSaveFile.name }
                if (existing != null) {
                    client.updateSave(existing.id, localSaveFile.absolutePath)
                    client.confirmSaveDownloaded(existing.id, deviceId)
                } else {
                    val uploaded = client.uploadSave(
                        romId = romId,
                        savePath = localSaveFile.absolutePath,
                        emulator = "cannoli",
                        deviceId = deviceId
                    )
                    client.confirmSaveDownloaded(uploaded.id, deviceId)
                }
                _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
            } catch (e: Exception) {
                _syncState.value = SyncState(SyncStatus.ERROR, e.message ?: "Upload failed")
            }
        }
    }

    fun downloadRemoteSave(platformTag: String, romName: String) {
        scope.launch {
            try {
                _syncState.value = SyncState(SyncStatus.SYNCING, "Downloading save...")
                val romId = findRomId(platformTag, romName) ?: return@launch
                val remoteSaves = client.getSaves(romId, deviceId)
                val latest = remoteSaves.maxByOrNull { parseIsoTime(it.updatedAt) } ?: return@launch
                downloadSave(latest, platformTag, romName)
                _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
            } catch (e: Exception) {
                _syncState.value = SyncState(SyncStatus.ERROR, e.message ?: "Download failed")
            }
        }
    }

    fun keepLocalSave() {
        _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
    }

    fun scanForChanges() {
        if (pendingChanges.isEmpty()) return
        val changes = pendingChanges.entries.toList()
        pendingChanges.clear()

        scope.launch {
            for ((path, _) in changes) {
                val file = File(path)
                if (!file.exists()) continue
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext !in saveExtensions) continue

                val parts = extractTagAndRom(file) ?: continue
                try {
                    _syncState.value = SyncState(SyncStatus.SYNCING, "Uploading ${file.name}...")
                    val romId = findRomId(parts.first, parts.second) ?: continue
                    val remoteSaves = client.getSaves(romId, deviceId)
                    val existing = remoteSaves.firstOrNull { it.fileName == file.name }
                    if (existing != null) {
                        client.updateSave(existing.id, file.absolutePath)
                        client.confirmSaveDownloaded(existing.id, deviceId)
                    } else {
                        val uploaded = client.uploadSave(
                            romId = romId,
                            savePath = file.absolutePath,
                            emulator = "external",
                            deviceId = deviceId
                        )
                        client.confirmSaveDownloaded(uploaded.id, deviceId)
                    }
                } catch (_: Exception) {}
            }
            _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
        }
    }

    private fun downloadSave(save: RommSave, platformTag: String, romName: String) {
        val data = client.downloadSave(save.id, deviceId)
        val saveDir = File(savesDir, platformTag)
        saveDir.mkdirs()
        val destFile = File(saveDir, save.fileName.ifEmpty { "$romName.srm" })
        val backup = File(destFile.absolutePath + ".bak")
        if (destFile.exists()) destFile.copyTo(backup, overwrite = true)
        destFile.writeBytes(data)
        client.confirmSaveDownloaded(save.id, deviceId)
    }

    private fun findLocalSave(platformTag: String, romName: String): File? {
        val saveDir = File(savesDir, platformTag)
        if (!saveDir.exists()) return null
        for (ext in saveExtensions) {
            val f = File(saveDir, "$romName.$ext")
            if (f.exists()) return f
        }
        return null
    }

    private fun findRomId(platformTag: String, romName: String): Int? {
        return try {
            val platforms = client.getPlatforms()
            val platform = platforms.firstOrNull { it.fsSlug.equals(platformTag, ignoreCase = true) }
                ?: return null
            val roms = client.getRoms(GetRomsQuery(
                platformId = platform.id,
                search = romName,
                limit = 5
            ))
            roms.items.firstOrNull {
                it.fsNameNoExt.equals(romName, ignoreCase = true) || it.name.equals(romName, ignoreCase = true)
            }?.id
        } catch (_: Exception) { null }
    }

    private fun extractTagAndRom(file: File): Pair<String, String>? {
        val savesPath = savesDir.absolutePath + "/"
        val filePath = file.absolutePath
        if (!filePath.startsWith(savesPath)) return null
        val relative = filePath.removePrefix(savesPath)
        val parts = relative.split("/")
        if (parts.size < 2) return null
        return parts[0] to file.nameWithoutExtension
    }

    private fun startWatching() {
        if (!savesDir.exists()) return
        fileObserver = object : FileObserver(savesDir, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                pendingChanges[File(savesDir, path).absolutePath] = System.currentTimeMillis()
            }
        }
        fileObserver?.startWatching()
    }

    private fun parseIsoTime(iso: String): Long {
        if (iso.isEmpty()) return 0
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(iso.substringBefore('.').substringBefore('+').substringBefore('Z'))?.time ?: 0
        } catch (_: Exception) { 0 }
    }
}
