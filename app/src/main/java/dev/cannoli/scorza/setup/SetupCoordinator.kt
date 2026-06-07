package dev.cannoli.scorza.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.util.LoggingPrefs
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.StorageLog
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var volumeMap: Map<String, String> = emptyMap()

    fun existingCannoliVolumeIndex(volumes: List<Pair<String, String>>): Int? {
        for (i in volumes.indices.reversed()) {
            val path = volumes[i].second
            if (path.isEmpty()) continue
            val cannoli = File(path, "Cannoli")
            if (cannoli.exists() && cannoli.isDirectory && File(cannoli, "Config/settings.json").exists()) {
                return i
            }
        }
        return null
    }

    fun detectStorageVolumes(): List<Pair<String, String>> {
        val volumes = mutableListOf("Internal Storage" to "/storage/emulated/0/")
        val sm = context.getSystemService(StorageManager::class.java)
        for (sv in sm.storageVolumes) {
            if (sv.isPrimary) continue
            val path = if (Build.VERSION.SDK_INT >= 30) {
                sv.directory?.absolutePath
            } else {
                try { sv.javaClass.getMethod("getPath").invoke(sv) as? String } catch (_: Exception) { null }
            } ?: continue
            val label = sv.getDescription(context) ?: File(path).name
            volumes.add(label to "$path/")
        }
        if (volumes.size == 1) {
            val storageDir = File("/storage")
            storageDir.listFiles()?.forEach { dir ->
                if (dir.name != "emulated" && dir.name != "self" && dir.isDirectory && dir.canRead()) {
                    volumes.add(dir.name to dir.absolutePath + "/")
                }
            }
        }
        return volumes
    }

    fun logStorageDiagnostics() {
        if (!LoggingPrefs.storage) return
        StorageLog.startRun("Storage scan")
        StorageLog.write("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_HASH}")
        StorageLog.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        StorageLog.write("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        StorageLog.write("Leanback (Android TV): $isTv")

        val sm = context.getSystemService(StorageManager::class.java)
        val volumes = try { sm.storageVolumes } catch (e: Exception) {
            StorageLog.write("ERROR reading storageVolumes: ${e.message}")
            emptyList()
        }
        StorageLog.write("StorageManager reports ${volumes.size} volume(s):")
        for (sv in volumes) {
            val dir30 = if (Build.VERSION.SDK_INT >= 30) sv.directory?.absolutePath else null
            val dirReflect = try { sv.javaClass.getMethod("getPath").invoke(sv) as? String } catch (_: Exception) { null }
            val removable = try { sv.isRemovable } catch (_: Exception) { null }
            val emulated = try { sv.isEmulated } catch (_: Exception) { null }
            StorageLog.write(
                "  - desc=${sv.getDescription(context)} state=${sv.state} primary=${sv.isPrimary} " +
                    "removable=$removable emulated=$emulated uuid=${sv.uuid} " +
                    "directory=$dir30 getPath=$dirReflect"
            )
        }

        StorageLog.write("getExternalFilesDirs:")
        try {
            context.getExternalFilesDirs(null).forEach { f ->
                StorageLog.write("  - ${f?.absolutePath}")
            }
        } catch (e: Exception) {
            StorageLog.write("  ERROR: ${e.message}")
        }

        StorageLog.write("/storage listing:")
        val storageDir = File("/storage")
        val entries = try { storageDir.listFiles() } catch (_: Exception) { null }
        if (entries == null) {
            StorageLog.write("  (unreadable or empty)")
        } else {
            for (dir in entries) {
                StorageLog.write("  - ${dir.name} isDirectory=${dir.isDirectory} canRead=${dir.canRead()}")
            }
        }

        val detected = detectStorageVolumes()
        StorageLog.write("detectStorageVolumes (wizard candidates), ${detected.size} entry(ies):")
        for ((label, path) in detected) {
            StorageLog.write("  - \"$label\" -> $path")
        }
    }

    fun listDirectories(path: String): List<String> {
        if (path == "/storage/") {
            val volumes = detectStorageVolumes()
            volumeMap = volumes.associate { (label, volPath) -> label to volPath }
            return volumes.map { it.first }
        }
        val dir = File(path)
        return dir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.map { it.name }
            ?.sortedWith(NaturalSort)
            ?: emptyList()
    }

    fun resolveDirectoryEntry(currentPath: String, entryName: String): String {
        if (currentPath == "/storage/") {
            return volumeMap[entryName] ?: "/storage/$entryName/"
        }
        return currentPath + entryName + "/"
    }

    fun parentDirectory(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed == "/storage") return null
        if (volumeMap.values.any { it.trimEnd('/') == trimmed }) return "/storage/"
        return if (trimmed.contains('/')) trimmed.substringBeforeLast('/') + "/" else null
    }

    fun isVolumeRoot(path: String): Boolean = detectStorageVolumes().any { it.second == path }

    /**
     * Invokes [onChange] when removable storage mounts/unmounts, so boot can re-evaluate setup once
     * a card the user already configured finishes mounting (vold delays the SD scan behind the
     * secure keyguard, so it can appear several seconds after the launcher starts). Returns a handle
     * whose [StorageWatch.stop] unregisters; safe to call stop more than once.
     */
    fun watchStorage(onChange: () -> Unit): StorageWatch {
        val sm = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val executor = Executor { it.run() }
            val callback = object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) { onChange() }
            }
            sm.registerStorageVolumeCallback(executor, callback)
            return StorageWatch { sm.unregisterStorageVolumeCallback(callback) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { onChange() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addDataScheme("file")
        }
        context.registerReceiver(receiver, filter)
        return StorageWatch { context.unregisterReceiver(receiver) }
    }

    fun interface StorageWatch {
        fun stop()
    }
}
