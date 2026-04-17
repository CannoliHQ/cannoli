package dev.cannoli.scorza.libretro

import android.os.Handler
import android.os.Looper
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.scorza.BuildConfig
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

class RetroAchievementsManager(
    private val context: android.content.Context? = null,
    private val cacheDir: java.io.File? = null,
    private val onEvent: (type: Int, title: String, description: String, points: Int) -> Unit = { _, _, _, _ -> },
    private val onLogin: (success: Boolean, displayName: String, token: String?) -> Unit = { _, _, _ -> },
    private val onSyncStatus: (message: String) -> Unit = {},
    private val logger: (String) -> Unit = {}
) {
    private val httpExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val userAgent: String by lazy {
        val clause = try { nativeGetUserAgentClause() } catch (_: Throwable) { "" }
        val base = "Cannoli/${BuildConfig.VERSION_NAME}"
        if (clause.isNotEmpty()) "$base $clause" else base
    }

    fun init() {
        logger("RA init")
        nativeInit()
        registerNetworkCallback()
    }

    fun destroy() {
        logger("RA destroy")
        unregisterNetworkCallback()
        httpExecutor.shutdown()
        try { httpExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        nativeDestroy()
    }

    fun loginWithToken(username: String, token: String) {
        nativeLoginWithToken(username, token)
    }

    fun loginWithPassword(username: String, password: String) {
        nativeLoginWithPassword(username, password)
    }

    fun loadGame(romPath: String, consoleId: Int) {
        logger("RA loadGame: romPath=$romPath consoleId=$consoleId")
        nativeLoadGame(romPath, consoleId)
    }

    fun loadGameById(gameId: Int, consoleId: Int) {
        logger("RA loadGameById: gameId=$gameId consoleId=$consoleId")
        nativeLoadGameById(gameId, consoleId)
    }

    fun unloadGame() {
        logger("RA unloadGame")
        nativeUnloadGame()
    }

    fun doFrame() {
        nativeDoFrame()
    }

    fun idle() {
        nativeIdle()
    }

    fun reset() {
        logger("RA reset")
        nativeReset()
    }

    fun serializeProgress(): ByteArray? {
        val data = nativeSerializeProgress()
        logger("RA serializeProgress: ${data?.size ?: 0} bytes")
        return data
    }

    fun deserializeProgress(data: ByteArray): Boolean {
        val ok = nativeDeserializeProgress(data)
        logger("RA deserializeProgress: ${data.size} bytes, success=$ok")
        return ok
    }

    val isLoggedIn: Boolean get() = nativeIsLoggedIn()
    val username: String get() = nativeGetUsername()
    val gameId: Int get() = nativeGetGameId()
    val gameTitle: String get() = nativeGetGameTitle()
    val isOnline: Boolean get() {
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        return cm.activeNetwork != null
    }

    private var cachedAchievements: List<Achievement>? = null
    val pendingSyncIds: MutableMap<Int, String> = Collections.synchronizedMap(mutableMapOf())
    val syncingIds: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    val localUnlocks: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    @Volatile private var syncExpectedCount = 0
    @Volatile private var syncSuccessCount = 0
    @Volatile private var syncFailCount = 0

    private fun syncPending() {
        if (pendingSyncIds.isEmpty() || !nativeIsLoggedIn()) return
        val toSync: Map<Int, String>
        synchronized(pendingSyncIds) {
            toSync = pendingSyncIds.toMap()
        }
        if (toSync.isEmpty()) return
        val count = toSync.size
        logger("RA syncPending: syncing $count achievements: ${toSync.keys}")
        syncExpectedCount = count
        syncSuccessCount = 0
        syncFailCount = 0
        synchronized(syncingIds) { syncingIds.addAll(toSync.keys) }
        cachedAchievements = null
        for ((id, hash) in toSync) nativeQueueUnlock(id, hash)
    }
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        if (context == null || networkCallback != null) return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                syncPending()
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }
    private val pendingSyncFile = cacheDir?.let { java.io.File(it, "pending_sync.txt") }

    init {
        pendingSyncFile?.let { file ->
            if (file.exists()) {
                try {
                    file.readLines().forEach { line ->
                        val parts = line.trim().split('|', limit = 2)
                        val id = parts[0].toIntOrNull() ?: return@forEach
                        val hash = if (parts.size > 1) parts[1] else ""
                        if (hash.isEmpty()) return@forEach
                        pendingSyncIds[id] = hash
                        localUnlocks.add(id)
                    }
                } catch (_: IOException) {}
            }
        }
    }

    private fun savePendingSync() {
        pendingSyncFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                synchronized(pendingSyncIds) {
                    if (pendingSyncIds.isEmpty()) file.delete()
                    else file.writeText(pendingSyncIds.entries.joinToString("\n") { "${it.key}|${it.value}" })
                }
            } catch (_: IOException) {}
        }
    }
    @Volatile var isOffline = false
        private set
    @Volatile private var networkConnected = true

    fun setPendingReset() {
        logger("RA setPendingReset")
        nativeSetPendingReset()
    }

    fun getAchievements(): List<Achievement> {
        cachedAchievements?.let { return it }
        val raw = nativeGetAchievementData()
        if (raw.isEmpty()) return emptyList()
        val list = raw.split('\n').mapNotNull { line ->
            val parts = line.split('|', limit = 7)
            if (parts.size < 7) return@mapNotNull null
            Achievement(
                id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                title = parts[1],
                description = parts[2],
                points = parts[3].toIntOrNull() ?: 0,
                unlocked = parts[4] == "1",
                state = parts[5].toIntOrNull() ?: 0,
                unlockTime = parts[6].toLongOrNull() ?: 0
            )
        }.filter { it.id > 0 && !it.title.startsWith("Warning:") && !it.title.startsWith("Unsupported") }
            .sortedBy { if (it.points == 0) 1 else 0 }
        cachedAchievements = list
        return list
    }

    fun invalidateCache() {
        cachedAchievements = null
    }

    data class Achievement(
        val id: Int,
        val title: String,
        val description: String,
        val points: Int,
        val unlocked: Boolean,
        val state: Int,
        val unlockTime: Long = 0,
        val pendingSync: Boolean = false
    )

    private fun cacheKey(postData: String?): String? {
        if (postData == null) return null
        val cacheable = postData.contains("r=achievementsets") || postData.contains("r=login2") || postData.contains("r=startsession")
        if (!cacheable) return null
        return postData.replace(CACHE_KEY_STRIP_REGEX, "")
            .hashCode().toUInt().toString(16)
    }

    private fun readCache(key: String): String? {
        val file = java.io.File(cacheDir ?: return null, "ra_$key.json")
        return if (file.exists()) try { file.readText() } catch (_: IOException) { null } else null
    }

    private fun writeCache(key: String, body: String) {
        val dir = cacheDir ?: return
        dir.mkdirs()
        try { java.io.File(dir, "ra_$key.json").writeText(body) } catch (_: IOException) {}
    }

    @Suppress("unused")
    private fun onServerCall(url: String, postData: String?, requestPtr: Long) {
        val urlTag = url.substringAfterLast("/").substringBefore("?").take(40)
        logger("RA http request: $urlTag")
        httpExecutor.execute {
            val key = cacheKey(postData)
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", userAgent)
                if (postData != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    OutputStreamWriter(conn.outputStream).use { it.write(postData) }
                }
                val status = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: IOException) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                logger("RA http response: $urlTag status=$status bodyLen=${body.length}")
                if (key != null && status == 200 && body.isNotEmpty()) writeCache(key, body)
                isOffline = false
                nativeHttpResponse(requestPtr, body, status)
            } catch (e: IOException) {
                val cached = if (key != null) readCache(key) else null
                if (cached != null) {
                    logger("RA http FAILED ($urlTag): ${e.message} -- using cache (len=${cached.length})")
                    isOffline = true
                    nativeHttpResponse(requestPtr, cached, 200)
                } else {
                    logger("RA http FAILED ($urlTag): ${e.message} -- no cache")
                    isOffline = true
                    nativeHttpResponse(requestPtr, "", RC_SERVER_ERROR)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    val pendingSyncCount: Int get() = pendingSyncIds.size

    fun getStatus(): String {
        val offline = !isOnline || isOffline
        return when {
            !offline -> "Online"
            pendingSyncIds.isEmpty() -> "Offline"
            else -> "Offline \u2022 ${pendingSyncIds.size} Pending Sync"
        }
    }

    @Suppress("unused")
    private fun onAchievementEvent(type: Int, achievementId: Int, title: String, description: String, points: Int) {
        logger("RA achievement event: type=$type id=$achievementId title=$title points=$points")

        if (type == EVENT_RECONNECTED) {
            logger("RA reconnected: rcheevos completed all pending awards")
            return
        }

        if (type == EVENT_DISCONNECTED) {
            logger("RA disconnected: rcheevos has pending awards that will retry")
            mainHandler.postDelayed({ onSyncStatus("Offline: Will Sync Later") }, 3500)
            return
        }

        if (achievementId > 0) {
            localUnlocks.add(achievementId)
            val hash = nativeGetGameHash()
            pendingSyncIds[achievementId] = hash
            savePendingSync()
            logger("RA achievement pending: id=$achievementId hash=$hash pendingCount=${pendingSyncIds.size}")
        }
        cachedAchievements = null
        mainHandler.post { onEvent(type, title, description, points) }
    }

    @Suppress("unused")
    private fun onLoginResult(success: Boolean, displayNameOrError: String, token: String?) {
        logger("RA login result: success=$success offline=$isOffline pendingSync=${pendingSyncIds.size}")
        if (success && !isOffline) syncPending()
        mainHandler.post { onLogin(success, displayNameOrError, token) }
    }

    @Suppress("unused")
    private fun onAwardResult(achievementId: Int, success: Boolean) {
        logger("RA award result: id=$achievementId success=$success")
        if (success) {
            pendingSyncIds.remove(achievementId)
            syncingIds.remove(achievementId)
            savePendingSync()
            syncSuccessCount++
        } else {
            syncingIds.remove(achievementId)
            syncFailCount++
            logger("RA award FAILED for id=$achievementId, keeping in pending queue")
        }
        val done = syncSuccessCount + syncFailCount
        if (done >= syncExpectedCount && syncExpectedCount > 0) {
            val msg = if (syncFailCount == 0) {
                "$syncSuccessCount ${if (syncSuccessCount == 1) "Achievement" else "Achievements"} Synced"
            } else {
                "$syncSuccessCount Synced, $syncFailCount Failed"
            }
            logger("RA sync complete: $msg")
            syncExpectedCount = 0
            mainHandler.post { onSyncStatus(msg) }
        }
    }

    @Suppress("unused")
    private fun onNativeLog(message: String) {
        logger("RA [native] $message")
    }

    private external fun nativeInit()
    private external fun nativeDestroy()
    private external fun nativeLoginWithToken(username: String, token: String)
    private external fun nativeLoginWithPassword(username: String, password: String)
    private external fun nativeLoadGame(romPath: String, consoleId: Int)
    private external fun nativeLoadGameById(gameId: Int, consoleId: Int)
    private external fun nativeUnloadGame()
    private external fun nativeDoFrame()
    private external fun nativeIdle()
    private external fun nativeReset()
    private external fun nativeIsLoggedIn(): Boolean
    private external fun nativeGetUsername(): String
    private external fun nativeGetGameId(): Int
    private external fun nativeGetGameTitle(): String
    private external fun nativeGetUserAgentClause(): String
    private external fun nativeHttpResponse(requestPtr: Long, body: String, httpStatus: Int)
    private external fun nativeGetAchievementData(): String
    private external fun nativeSerializeProgress(): ByteArray?
    private external fun nativeDeserializeProgress(data: ByteArray): Boolean
    private external fun nativeQueueUnlock(achievementId: Int, gameHash: String)
    private external fun nativeGetGameHash(): String
    private external fun nativeSetPendingReset()

    companion object {
        init {
            System.loadLibrary("retro_bridge")
        }

        private const val RC_SERVER_ERROR = 503
        private const val EVENT_DISCONNECTED = 17
        private const val EVENT_RECONNECTED = 18
        private val CACHE_KEY_STRIP_REGEX = Regex("[&?](t|u)=[^&]+")

        val CONSOLE_MAP = mapOf(
            "NES" to 7, "FDS" to 7, "SNES" to 3, "GB" to 4, "GBC" to 6, "GBA" to 5,
            "GG" to 15, "MD" to 1, "SMS" to 11, "SG1000" to 33, "SEGACD" to 9, "32X" to 10,
            "N64" to 2, "PS" to 12, "PSP" to 41,
            "PCE" to 8, "SUPERGRAFX" to 8, "PCFX" to 49,
            "LYNX" to 13,
            "NGP" to 14, "NGPC" to 14,
            "WS" to 53, "WSC" to 53,
            "VIRTUALBOY" to 28, "POKEMINI" to 71,
            "ATARI2600" to 25, "ATARI7800" to 51, "JAGUAR" to 17,
            "INTELLIVISION" to 45, "COLECOVISION" to 44, "VECTREX" to 46,
            "DC" to 40, "SATURN" to 39,
            "NEOGEO" to 27, "FBN" to 27, "MAME" to 27,
            "MSX" to 29, "DOS" to 68, "SCUMMVM" to 56, "AMIGA" to 35
        )
    }
}

fun RetroAchievementsManager.Achievement.toAchievementInfo() = AchievementInfo(
    id = id,
    title = title,
    description = description,
    points = points,
    unlocked = unlocked,
    state = state,
    unlockTime = unlockTime,
    pendingSync = pendingSync
)
