package dev.cannoli.scorza.setup

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.scanner.CollectionManager
import dev.cannoli.scorza.scanner.CoreInfoRepository
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.scanner.OrderingManager
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.scanner.RecentlyPlayedManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.ui.ELLIPSIS
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledServices(
    val platformResolver: PlatformResolver,
    val collectionManager: CollectionManager,
    val recentlyPlayedManager: RecentlyPlayedManager,
    val orderingManager: OrderingManager,
    val scanner: FileScanner,
    val retroArchLauncher: RetroArchLauncher,
    val emuLauncher: EmuLauncher,
    val apkLauncher: ApkLauncher,
    val installedCoreService: InstalledCoreService,
    val launchManager: LaunchManager,
)

class SetupCoordinator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val ioScope: CoroutineScope,
) {
    private var volumeMap: Map<String, String> = emptyMap()

    fun detectExistingCannoli(): String? {
        val volumes = detectStorageVolumes()
        for ((_, path) in volumes.reversed()) {
            val cannoli = File(path, "Cannoli")
            if (cannoli.exists() && cannoli.isDirectory && File(cannoli, "Config/settings.json").exists()) {
                return cannoli.absolutePath + "/"
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

    fun startInstalling(
        targetPath: String,
        onProgress: (progress: Float, label: String) -> Unit,
        onFinished: (services: InstalledServices) -> Unit,
    ) {
        val labels = listOf(
            "Kneading the dough$ELLIPSIS",
            "Rolling the shells$ELLIPSIS",
            "Heating the oil$ELLIPSIS",
            "Frying the shells$ELLIPSIS",
            "Making the filling$ELLIPSIS",
            "Piping the rigott$ELLIPSIS"
        )

        ioScope.launch {
            val root = File(targetPath)
            val coreInfo = CoreInfoRepository(context.assets, context.filesDir, File(context.applicationInfo.sourceDir).lastModified())
            coreInfo.load()
            val bundledCoresDir = LaunchManager.extractBundledCores(context)
            val platformResolver = PlatformResolver(root, context.assets, coreInfo, bundledCoresDir)
            platformResolver.load()

            val collectionManager = CollectionManager(root)
            val recentlyPlayedManager = RecentlyPlayedManager(root)
            val orderingManager = OrderingManager(root)
            val localScanner = FileScanner(root, platformResolver, collectionManager, context.assets)
            localScanner.loadIgnoreExtensions()
            localScanner.loadIgnoreFiles()

            val overhead = 7
            val dirCount = 18 + (platformResolver.getAllTags().size * 6)
            val totalSteps = dirCount + overhead
            var completed = 0

            fun step() {
                completed++
                val p = completed.toFloat() / totalSteps
                val labelIndex = (p * labels.size).toInt().coerceIn(0, labels.lastIndex)
                onProgress(p, labels[labelIndex])
            }

            localScanner.ensureDirectories { step() }

            val retroArchLauncher = RetroArchLauncher(context) { settings.retroArchPackage }; step()
            val emuLauncher = EmuLauncher(context); step()
            val apkLauncher = ApkLauncher(context); step()
            val installedCoreService = InstalledCoreService(context); step()
            val lm = LaunchManager(context, settings, platformResolver, retroArchLauncher, emuLauncher, apkLauncher, installedCoreService); step()
            lm.syncRetroArchAssets(root); step()
            lm.syncRetroArchConfig(root); step()

            val services = InstalledServices(
                platformResolver = platformResolver,
                collectionManager = collectionManager,
                recentlyPlayedManager = recentlyPlayedManager,
                orderingManager = orderingManager,
                scanner = localScanner,
                retroArchLauncher = retroArchLauncher,
                emuLauncher = emuLauncher,
                apkLauncher = apkLauncher,
                installedCoreService = installedCoreService,
                launchManager = lm,
            )

            withContext(Dispatchers.Main) {
                onFinished(services)
            }
        }
    }
}
