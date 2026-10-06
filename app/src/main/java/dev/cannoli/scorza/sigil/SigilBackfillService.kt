package dev.cannoli.scorza.sigil

import dev.cannoli.scorza.db.GameIdRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.util.RomDirectoryWalker
import dev.cannoli.scorza.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SigilBackfillService @Inject constructor(
    repository: GameIdRepository,
    walker: RomDirectoryWalker,
    scanScheduler: ScanScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var active = false

    private val engine = SigilBackfillEngine(
        repository = repository,
        gameFiles = walker::gameFiles,
        log = ScanLog::write,
    )

    init {
        scope.launch {
            scanScheduler.results.collect { result ->
                if (result.platformTag.uppercase() in SigilPlatform.TAGS) nudges.trySend(Unit)
            }
        }
        scope.launch {
            for (nudge in nudges) {
                if (!active) continue
                val probed = engine.drain(isActive = { active }, yieldBetween = { yield() })
                if (probed > 0) ScanLog.write("sigil: probed $probed roms")
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) nudges.trySend(Unit)
    }
}
