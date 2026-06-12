package dev.cannoli.scorza.romm.art

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.cache.RommDatabase
import dev.cannoli.scorza.romm.download.RommDownloader
import dev.cannoli.scorza.util.ArtworkLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

data class ArtFetchResults(
    val added: Int = 0,
    val alreadyHadArt: Int = 0,
    val noMatch: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)

sealed interface ArtFetchState {
    data object Idle : ArtFetchState
    data object Running : ArtFetchState
    data class Finished(val results: ArtFetchResults) : ArtFetchState
}

class RommArtFetcher(
    private val roms: RomsRepository,
    private val artwork: ArtworkLookup,
    private val db: RommDatabase,
    private val links: RommLinkRepository,
    private val store: RommConnectionStore,
    private val artDownloader: RommArtDownloader,
    private val paths: CannoliPathsProvider,
    private val concurrency: () -> Int,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ArtFetchState>(ArtFetchState.Idle)
    val state: StateFlow<ArtFetchState> = _state

    private var job: Job? = null

    fun isRunning(): Boolean = _state.value == ArtFetchState.Running

    @Synchronized
    fun start(tags: List<String>) {
        if (job?.isActive == true) return
        _state.value = ArtFetchState.Running
        job = scope.launch(Dispatchers.IO) { _state.value = ArtFetchState.Finished(run(tags)) }
    }

    fun dismissResults() { _state.value = ArtFetchState.Idle }

    private suspend fun run(tags: List<String>): ArtFetchResults = coroutineScope {
        val added = AtomicInteger(0)
        var alreadyHadArt = 0
        val noMatch = mutableListOf<String>()
        val failed = Collections.synchronizedList(mutableListOf<String>())
        val host = store.host
        val platformsByTag = db.platforms().associateBy { it.cannoliTag.lowercase() }
        val romRoot = paths.romDir
        val gate = Semaphore(concurrency().coerceIn(1, RommDownloader.MAX_CONCURRENCY))
        val downloads = mutableListOf<Job>()
        val processedTags = mutableListOf<String>()

        // Matching is sequential (cheap DB/file work); only the cover downloads run concurrently.
        for (tag in tags) {
            val platform = platformsByTag[tag.lowercase()] ?: continue
            processedTags.add(tag)
            val cached = db.allGames(platform.id)
            val byFsName: Map<String, RommGame> = cached.associateBy { it.fsName.lowercase() }
            val byId: Map<Int, RommGame> = cached.associateBy { it.id }

            for (rom in roms.allRomsForPlatform(tag)) {
                val file: File = rom.path
                val hasArt = artwork.find(tag, file) != null
                val relative = file.absolutePath
                    .removePrefix(romRoot.absolutePath).removePrefix(File.separator)
                val linkedId = links.rommIdForPath(relative)
                when (val outcome = RommArtMatcher.decide(hasArt, file.name, linkedId, byFsName, byId)) {
                    is ArtOutcome.AlreadyHasArt -> alreadyHadArt++
                    is ArtOutcome.NoMatch -> noMatch.add(rom.displayName)
                    is ArtOutcome.Found -> {
                        val baseName = file.nameWithoutExtension
                        val coverPath = outcome.game.coverPath
                        val displayName = rom.displayName
                        downloads.add(launch(Dispatchers.IO) {
                            gate.withPermit {
                                if (artDownloader.download(host, coverPath, tag, baseName)) added.incrementAndGet()
                                else failed.add(displayName)
                            }
                        })
                    }
                }
            }
        }

        downloads.joinAll()
        processedTags.forEach { artwork.invalidate(it) }
        ArtFetchResults(added.get(), alreadyHadArt, noMatch.toList(), failed.toList())
    }
}
