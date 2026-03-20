package dev.cannoli.scorza.romm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    private val romsDir = File(cannoliRoot, "Roms")
    private val cacheDir = File(cannoliRoot, "Config")

    private val _syncState = MutableStateFlow(SyncState(status = SyncStatus.UP_TO_DATE))
    val syncState: StateFlow<SyncState> = _syncState

    private val lastSyncTimes = ConcurrentHashMap<String, Long>()
    private var cache: RommCache? = null
    private var tagToPlatformSlug = mapOf<String, String>()
    @Volatile private var syncing = false

    private val saveExtensions = setOf(
        "srm", "sav", "dsv", "mcr", "mcd", "brm", "eep", "sra", "fla", "mpk", "nv"
    )

    // Cannoli tag -> RomM fsSlug (derived from Grout's CFW platform mappings)
    private val cannoliToRomm = mapOf(
        "gb" to "gb",
        "gbc" to "gbc",
        "gba" to "gba",
        "nes" to "nes",
        "fds" to "fds",
        "snes" to "snes",
        "n64" to "n64",
        "nds" to "nds",
        "3ds" to "3ds",
        "gg" to "gamegear",
        "sms" to "sms",
        "md" to "genesis",
        "sg1000" to "sg1000",
        "32x" to "sega32",
        "segacd" to "segacd",
        "saturn" to "saturn",
        "ps" to "psx",
        "ps2" to "ps2",
        "ps3" to "ps3",
        "psp" to "psp",
        "psvita" to "psvita",
        "dc" to "dc",
        "gc" to "ngc",
        "wii" to "wii",
        "wiiu" to "wiiu",
        "nsw" to "switch",
        "lynx" to "lynx",
        "jaguar" to "jaguar",
        "atari2600" to "atari2600",
        "atari5200" to "atari5200",
        "atari7800" to "atari7800",
        "pce" to "tg16",
        "pcfx" to "pc-fx",
        "ngp" to "neo-geo-pocket",
        "ngpc" to "neo-geo-pocket-color",
        "ws" to "wonderswan",
        "wsc" to "wonderswan-color",
        "neogeo" to "neogeomvs",
        "mame" to "arcade",
        "fbn" to "arcade",
        "virtualboy" to "virtualboy",
        "pokemini" to "pokemon-mini",
        "colecovision" to "colecovision",
        "vectrex" to "vectrex",
        "intellivision" to "intellivision",
        "supergrafx" to "supergrafx",
        "dos" to "dos",
        "scummvm" to "scummvm",
        "amiga" to "amiga"
    )

    private fun log(msg: String) {
        try {
            val logFile = File(cannoliRoot, "Config/sync.log")
            logFile.appendText("${java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())} $msg\n")
        } catch (_: Exception) {}
    }

    fun start() {}
    fun stop() {
        cache?.close()
        cache = null
    }

    fun fullSync() {
        if (syncing) return
        log("fullSync() called")
        scope.launch {
            if (syncing) return@launch
            syncing = true
            try {
                _syncState.value = SyncState(SyncStatus.SYNCING, "Building cache...")
                ensureCache()
                val db = cache ?: run {
                    _syncState.value = SyncState(SyncStatus.ERROR, "Cache init failed")
                    return@launch
                }

                val localRoms = scanAllLocalRoms()
                if (localRoms.isEmpty()) {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE, "No local ROMs found")
                    return@launch
                }

                log("Found ${localRoms.size} local ROMs, looking up in cache (${db.gameCount()} games)")
                _syncState.value = SyncState(SyncStatus.SYNCING, "Matching ${localRoms.size} ROMs...")

                var matched = 0
                var uploaded = 0
                var downloaded = 0
                var upToDate = 0
                var noplatform = 0
                var norom = 0

                for ((tag, romName) in localRoms) {
                    val fsSlug = resolveSlug(tag)
                    if (fsSlug == null) { noplatform++; continue }

                    val romId = db.findRomId(fsSlug, romName)
                    if (romId == null) { norom++; continue }

                    matched++
                    _syncState.value = SyncState(SyncStatus.SYNCING, "Syncing $romName ($matched matched)...")

                    val localSave = findLocalSave(tag, romName)
                    try {
                        val remoteSaves = client.getSaves(romId, deviceId)
                        val existing = if (localSave != null) remoteSaves.firstOrNull { it.fileName == localSave.name } else null

                        if (localSave != null && localSave.exists()) {
                            if (existing != null) {
                                val remoteTime = parseIsoTime(existing.updatedAt)
                                val localTime = localSave.lastModified()
                                if (localTime > remoteTime + 1000) {
                                    client.updateSave(existing.id, localSave.absolutePath)
                                    client.confirmSaveDownloaded(existing.id, deviceId)
                                    lastSyncTimes[localSave.absolutePath] = localSave.lastModified()
                                    log("  UPLOADED $tag/$romName — local newer (romId=$romId, ${localSave.length()} bytes)")
                                    uploaded++
                                } else if (remoteTime > localTime + 1000) {
                                    downloadSave(existing, tag, romName)
                                    log("  DOWNLOADED $tag/$romName — remote newer (romId=$romId)")
                                    downloaded++
                                } else {
                                    log("  UP-TO-DATE $tag/$romName")
                                    upToDate++
                                }
                            } else {
                                val up = client.uploadSave(romId, localSave.absolutePath, "cannoli", deviceId)
                                client.confirmSaveDownloaded(up.id, deviceId)
                                lastSyncTimes[localSave.absolutePath] = localSave.lastModified()
                                log("  UPLOADED $tag/$romName (romId=$romId, ${localSave.length()} bytes)")
                                uploaded++
                            }
                        } else if (remoteSaves.isNotEmpty()) {
                            val latest = remoteSaves.maxBy { parseIsoTime(it.updatedAt) }
                            downloadSave(latest, tag, romName)
                            log("  DOWNLOADED $tag/$romName (romId=$romId, saved as $romName.${latest.fileExtension.ifEmpty { "srm" }})")
                            downloaded++
                        } else {
                            log("  NO-SAVE $tag/$romName (romId=$romId)")
                            upToDate++
                        }
                    } catch (e: Exception) {
                        log("  FAIL $tag/$romName — ${e.message}")
                    }
                }

                val msg = buildString {
                    append("$matched matched")
                    if (uploaded > 0) append(", $uploaded uploaded")
                    if (downloaded > 0) append(", $downloaded downloaded")
                    if (upToDate > 0) append(", $upToDate up to date")
                    if (norom > 0) append(", $norom not in RomM")
                }
                log("fullSync done: $msg")
                _syncState.value = SyncState(SyncStatus.UP_TO_DATE, msg)
            } catch (e: Exception) {
                log("fullSync failed: ${e.message}")
                _syncState.value = SyncState(SyncStatus.ERROR, e.message ?: "Sync failed")
            } finally {
                syncing = false
            }
        }
    }

    fun syncOnLaunch(platformTag: String, romName: String, onDone: () -> Unit, onConflict: (String) -> Unit) {
        if (cache == null || !cache!!.hasCache()) { onDone(); return }
        scope.launch {
            try {
                _syncState.value = SyncState(SyncStatus.SYNCING, "Checking saves...")
                val fsSlug = resolveSlug(platformTag)
                if (fsSlug == null) {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
                    return@launch
                }
                val romId = cache?.findRomId(fsSlug, romName)
                if (romId == null) {
                    _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
                    return@launch
                }

                val remoteSaves = client.getSaves(romId, deviceId)
                val localSaveFile = findLocalSave(platformTag, romName)

                if (remoteSaves.isNotEmpty()) {
                    val latest = remoteSaves.maxBy { parseIsoTime(it.updatedAt) }
                    if (!latest.isDeviceSynced(deviceId)) {
                        if (localSaveFile != null && localSaveFile.exists()) {
                            val remoteTime = parseIsoTime(latest.updatedAt)
                            if (remoteTime > localSaveFile.lastModified() + 1000) {
                                downloadSave(latest, platformTag, romName)
                                log("  PRE-LAUNCH DOWNLOADED $platformTag/$romName")
                            }
                        } else {
                            downloadSave(latest, platformTag, romName)
                            log("  PRE-LAUNCH DOWNLOADED $platformTag/$romName")
                        }
                    }
                }

                _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
            } catch (e: Exception) {
                log("  PRE-LAUNCH FAIL $platformTag/$romName — ${e.message}")
                _syncState.value = SyncState(SyncStatus.ERROR, e.message ?: "Sync failed")
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    fun scanForChanges() {
        scope.launch {
            try {
                ensureCache()
                val changed = scanAllLocalSaves().filter { (_, _, file) ->
                    val lastSync = lastSyncTimes[file.absolutePath] ?: 0L
                    file.lastModified() > lastSync
                }
                if (changed.isEmpty()) return@launch

                _syncState.value = SyncState(SyncStatus.SYNCING, "Uploading ${changed.size} saves...")
                for ((tag, romName, file) in changed) {
                    val fsSlug = resolveSlug(tag) ?: continue
                    val romId = cache?.findRomId(fsSlug, romName) ?: continue
                    try {
                        uploadIfNeeded(romId, file)
                        lastSyncTimes[file.absolutePath] = file.lastModified()
                    } catch (_: Exception) {}
                }
                _syncState.value = SyncState(SyncStatus.UP_TO_DATE)
            } catch (_: Exception) {}
        }
    }

    fun downloadRemoteSave(platformTag: String, romName: String) {
        scope.launch {
            try {
                _syncState.value = SyncState(SyncStatus.SYNCING, "Downloading save...")
                ensureCache()
                val fsSlug = resolveSlug(platformTag) ?: return@launch
                val romId = cache?.findRomId(fsSlug, romName) ?: return@launch
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

    private fun ensureCache() {
        if (cache == null) {
            cache = RommCache(cacheDir)
        }
        val db = cache!!
        db.sync(client) { msg -> log(msg); _syncState.value = SyncState(SyncStatus.SYNCING, msg) }
        buildSlugMapping(db)
    }

    private fun buildSlugMapping(db: RommCache) {
        val map = mutableMapOf<String, String>()
        val binding = try { client.getPlatformsBinding() } catch (_: Exception) { emptyMap() }

        for (p in db.getPlatforms()) {
            map[p.fsSlug.lowercase(Locale.ROOT)] = p.fsSlug
            binding[p.fsSlug]?.let { map[it.lowercase(Locale.ROOT)] = p.fsSlug }
        }

        for ((cannoliTag, rommSlug) in cannoliToRomm) {
            if (cannoliTag !in map) {
                val actualSlug = map[rommSlug]
                if (actualSlug != null) map[cannoliTag] = actualSlug
            }
        }

        tagToPlatformSlug = map
    }

    private fun resolveSlug(tag: String): String? = tagToPlatformSlug[tag.lowercase(Locale.ROOT)]

    private fun uploadIfNeeded(romId: Int, file: File): String {
        val remoteSaves = client.getSaves(romId, deviceId)
        val existing = remoteSaves.firstOrNull { it.fileName == file.name }
        return if (existing != null) {
            if (existing.isDeviceSynced(deviceId) && file.lastModified() <= parseIsoTime(existing.updatedAt) + 1000) {
                "UP-TO-DATE"
            } else {
                client.updateSave(existing.id, file.absolutePath)
                client.confirmSaveDownloaded(existing.id, deviceId)
                "UPDATED"
            }
        } else {
            val uploaded = client.uploadSave(
                romId = romId,
                savePath = file.absolutePath,
                emulator = "cannoli",
                deviceId = deviceId
            )
            client.confirmSaveDownloaded(uploaded.id, deviceId)
            "UPLOADED"
        }
    }

    private fun downloadSave(save: RommSave, platformTag: String, romName: String) {
        val data = client.downloadSave(save.id, deviceId)
        val saveDir = File(savesDir, platformTag)
        saveDir.mkdirs()
        val ext = save.fileExtension.ifEmpty { "srm" }
        val destFile = File(saveDir, "$romName.$ext")
        val backup = File(destFile.absolutePath + ".bak")
        if (destFile.exists()) destFile.copyTo(backup, overwrite = true)
        destFile.writeBytes(data)
        client.confirmSaveDownloaded(save.id, deviceId)
        lastSyncTimes[destFile.absolutePath] = destFile.lastModified()
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

    private data class LocalRom(val tag: String, val romName: String)

    private fun scanAllLocalRoms(): List<LocalRom> {
        if (!romsDir.exists()) return emptyList()
        val result = mutableListOf<LocalRom>()
        val seen = mutableSetOf<String>()
        val tagDirs = romsDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        for (tagDir in tagDirs) {
            val tag = tagDir.name
            val files = tagDir.listFiles() ?: continue
            for (file in files) {
                if (file.name == ".emu_launch" || file.name == "map.txt") continue
                val romName = file.nameWithoutExtension
                val key = "$tag/$romName"
                if (key !in seen) {
                    seen.add(key)
                    result.add(LocalRom(tag, romName))
                }
            }
        }
        return result
    }

    private data class LocalSave(val tag: String, val romName: String, val file: File)

    private fun scanAllLocalSaves(): List<LocalSave> {
        if (!savesDir.exists()) return emptyList()
        val result = mutableListOf<LocalSave>()
        val tagDirs = savesDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        for (tagDir in tagDirs) {
            val tag = tagDir.name
            val files = tagDir.listFiles { f ->
                f.isFile && f.extension.lowercase(Locale.ROOT) in saveExtensions
            } ?: continue
            for (file in files) {
                result.add(LocalSave(tag, file.nameWithoutExtension, file))
            }
        }
        return result
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
