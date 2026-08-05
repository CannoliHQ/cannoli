package dev.cannoli.scorza.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class InstalledCoreService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var installedCores: Map<String, Set<String>> = emptyMap()
        private set

    @Volatile
    var unresponsivePackages: Set<String> = emptySet()
        private set

    @Volatile
    var cacheReady: Boolean = false
        private set

    // Cores marked installed since the current query began, unioned back in at publish time.
    private val markedSinceQuery = mutableMapOf<String, MutableSet<String>>()

    suspend fun queryAllPackages() {
        synchronized(this) { markedSinceQuery.clear() }
        val answered = mutableMapOf<String, Set<String>>()
        val silent = mutableSetOf<String>()
        for (pkg in discoverRaPackages()) {
            // An empty reply is not the same as no reply. A freshly installed RicottaArch has
            // no cores by design and genuinely answers zero; treating that as unresponsive made
            // the shipped default package skip the missing-core check and report every platform
            // as ready.
            val cores = queryPackage(pkg)
            if (cores == null) silent.add(pkg) else answered[pkg] = cores
        }
        publishQueryResult(answered, silent)
    }

    // Synchronized on the same monitor as markInstalled so the two writers never interleave.
    @Synchronized
    private fun publishQueryResult(answered: Map<String, Set<String>>, silent: Set<String>) {
        val merged = HashMap<String, Set<String>>()
        // A package that did not answer keeps whatever was cached, since there is no fresh
        // information about it.
        for ((pkg, cores) in installedCores) if (pkg !in answered) merged[pkg] = cores
        // A package that did answer is replaced wholesale, so a core deleted inside RetroArch
        // stops being reported. Unioning everything used to make deletions invisible forever.
        // Cores marked installed while this query was in flight are re-added, which is what the
        // blanket union was really protecting.
        for ((pkg, cores) in answered) merged[pkg] = cores + markedSinceQuery[pkg].orEmpty()
        markedSinceQuery.clear()
        installedCores = merged
        unresponsivePackages = silent
        cacheReady = true
    }

    private fun discoverRaPackages(): List<String> {
        return context.packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it.startsWith("com.retroarch") || it.startsWith("dev.cannoli.ricotta") }
    }

    /** Null means the package never answered. An empty set means it answered with no cores. */
    private suspend fun queryPackage(pkg: String, timeoutMs: Long = 3000L): Set<String>? =
        suspendCancellableCoroutine { cont ->
            val token = Any()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val cores = intent.getStringArrayExtra("CORES")
                        ?.map { soToCoreId(it) }?.toSet() ?: emptySet()
                    handler.removeCallbacksAndMessages(token)
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(cores)
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter("com.retroarch.INSTALLED_CORES_RESULT"),
                Context.RECEIVER_EXPORTED
            )

            context.sendBroadcast(Intent("com.retroarch.QUERY_INSTALLED_CORES").apply {
                setPackage(pkg)
            })

            handler.postAtTime({
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) cont.resume(null)
            }, token, android.os.SystemClock.uptimeMillis() + timeoutMs)

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                handler.removeCallbacksAndMessages(token)
            }
        }

    // installedCores is only ever populated by querying other apps. For the in-APK RetroArch
    // there is no query, so answer from the directory the same way configuredCores does.
    fun hasCoreInPackage(coreId: String, pkg: String): Boolean =
        if (isEmbedded(pkg)) coreId in localCores()
        else installedCores[pkg]?.contains(coreId) == true

    @Synchronized
    fun markInstalled(pkg: String, coreId: String) {
        val existing = installedCores[pkg].orEmpty()
        installedCores = installedCores + (pkg to existing + coreId)
        unresponsivePackages = unresponsivePackages - pkg
        // Recorded so a query that started before this download cannot publish a stale set
        // that omits the core we just installed.
        markedSinceQuery.getOrPut(pkg) { mutableSetOf() }.add(coreId)
    }

    // Core ids, not filenames. This set is compared against a resolved core id (markInstalled
    // stores ids too, and raAvailable does value.contains(resolvedCore)), so returning
    // "mgba_libretro_android.so" here would never match "mgba_libretro".
    private fun localCores(): Set<String> =
        File(context.filesDir, "cores").listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith("_android.so") }
            ?.map { it.removeSuffix("_android.so") }
            ?.toSet()
            ?: emptySet()

    // Empty (unset) or this app's own package both mean the in-APK RetroArch.
    private fun isEmbedded(pkg: String): Boolean = pkg.isEmpty() || pkg == context.packageName

    fun configuredCores(): Map<String, Set<String>> {
        val pkg = settings.retroArchPackage
        if (isEmbedded(pkg)) return mapOf(pkg to localCores())
        return installedCores.filterKeys { it == pkg }
    }

    fun configuredUnresponsive(): Set<String> {
        val pkg = settings.retroArchPackage
        if (pkg.isEmpty() || pkg == context.packageName) return emptySet()
        return if (pkg in unresponsivePackages) setOf(pkg) else emptySet()
    }

    companion object {
        private val PACKAGE_LABELS = mapOf(
            "dev.cannoli.ricotta.aarch64" to "RicottaArch",
            "dev.cannoli.ricotta" to "RicottaArch",
            "com.retroarch.aarch64" to "RetroArch",
            "com.retroarch" to "RetroArch"
        )

        fun getPackageLabel(pkg: String): String = PACKAGE_LABELS[pkg] ?: pkg

        fun soToCoreId(filename: String): String =
            filename.removeSuffix("_android.so").removeSuffix(".so")
    }
}
