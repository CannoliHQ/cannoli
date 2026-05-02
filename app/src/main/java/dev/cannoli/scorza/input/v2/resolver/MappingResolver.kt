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

    fun resolve(device: ConnectedDevice, bluetoothMac: String? = null): ResolvedMapping {
        val matchInput = device.toMatchInput(bluetoothMac)

        val candidates = repository.list()
            .filter { mapping ->
                // Hard reject: saved BT mapping with a different MAC than the current device's
                // is a different physical controller. Two 8BitDo Lites share name+VID/PID but
                // are distinct hardware identified by MAC; one's saved INI must not adopt the
                // other.
                val savedMac = mapping.match.bluetoothMac
                val inputMac = matchInput.bluetoothMac
                !(savedMac != null && savedMac.isNotEmpty() &&
                    inputMac != null && inputMac.isNotEmpty() &&
                    !savedMac.equals(inputMac, ignoreCase = true))
            }
            .map { it to it.match.score(matchInput) }
            .filter { it.second > 0 }
            .filter { (mapping, _) ->
                // Don't let a clone with the same VID/PID grab the handheld built-in's saved
                // mapping. If a non-BT mapping's displayName is in the wired-only list, it
                // represents the built-in pad (or another wired-only device). Require the
                // InputDevice's name to match so a renamed liar can't ride in on VID/PID alone.
                val isBuiltInMapping = mapping.match.bluetoothMac.isNullOrEmpty() &&
                    hints.isWiredOnly(mapping.displayName)
                !isBuiltInMapping || matchInput.name.equals(mapping.displayName, ignoreCase = true)
            }
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
                RetroArchAutoconfigImporter.import(raMatch, device, hints, bluetoothMac),
                persistent = false,
            )
        }

        return ResolvedMapping(
            AndroidDefaultMappingFactory.create(device, hints, bluetoothMac),
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
