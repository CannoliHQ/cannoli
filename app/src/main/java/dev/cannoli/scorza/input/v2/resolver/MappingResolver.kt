package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.hints.ControllerHintTable
import dev.cannoli.scorza.input.v2.repo.MappingRepository
import java.io.File

data class ResolvedMapping(
    val mapping: DeviceMapping,
    val persistent: Boolean,
)

class MappingResolver(
    private val repository: MappingRepository,
    private val bundledRetroArchEntries: List<RetroArchCfgEntry>,
    private val hints: ControllerHintTable,
    private val mappingsDir: File? = null,
) {

    /**
     * Resolve a connected device to its mapping.
     *
     * [persistenceDescriptor] is the identifier the caller wants the resulting mapping to be
     * persisted under. Callers (the bridge) compute this from sibling-folded InputDevices — the
     * gamepad's own descriptor when it's unique, or a sibling's descriptor on Retroid-style
     * phantom-rewrite hosts where the gamepad endpoint has a degenerate (empty-uniqueId) hash.
     * Null means "use the device's own descriptor (or none)".
     */
    fun resolve(device: ConnectedDevice, persistenceDescriptor: String? = null): ResolvedMapping {
        val matchInput = device.toMatchInput(
            descriptor = persistenceDescriptor?.takeIf { it.isNotEmpty() }
                ?: device.descriptor.takeIf { it.isNotEmpty() }
        )

        val candidates = repository.list()
            .map { it to it.match.score(matchInput) }
            .filter { it.second > 0 }
        if (candidates.isNotEmpty()) {
            val best = candidates.maxWithOrNull(
                compareBy<Pair<DeviceMapping, Int>>({ it.second })
                    .thenBy { mappingsDir?.let { dir -> File(dir, "${it.first.id}.ini").lastModified() } ?: 0L }
            )
            if (best != null) return ResolvedMapping(best.first, persistent = true)
        }

        val raMatch = bestRetroArchEntry(device)
        if (raMatch != null) {
            return ResolvedMapping(
                RetroArchAutoconfigImporter.import(raMatch, device, hints, persistenceDescriptor),
                persistent = false,
            )
        }

        return ResolvedMapping(
            AndroidDefaultMappingFactory.create(device, hints, persistenceDescriptor),
            persistent = false,
        )
    }

    private fun bestRetroArchEntry(device: ConnectedDevice): RetroArchCfgEntry? {
        var best: RetroArchCfgEntry? = null
        var bestScore = 0
        for (entry in bundledRetroArchEntries) {
            val score = scoreEntry(entry, device)
            if (score > bestScore) {
                best = entry
                bestScore = score
            }
        }
        return if (bestScore >= 30) best else null
    }

    private fun scoreEntry(entry: RetroArchCfgEntry, device: ConnectedDevice): Int {
        val nameMatch = entry.deviceName.isNotEmpty() && entry.deviceName == device.name
        val hasVidPid = device.vendorId != 0 && device.productId != 0 &&
            entry.vendorId != null && entry.productId != null
        val vidPidMatch = hasVidPid &&
            entry.vendorId == device.vendorId &&
            entry.productId == device.productId
        return when {
            nameMatch && vidPidMatch -> 50
            vidPidMatch -> 30
            nameMatch -> 20
            else -> 0
        }
    }
}
