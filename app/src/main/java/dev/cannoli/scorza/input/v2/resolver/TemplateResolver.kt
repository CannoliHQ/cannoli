package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.repo.TemplateRepository
import java.io.File

data class ResolvedTemplate(
    val template: DeviceTemplate,
    val persistent: Boolean,
)

class TemplateResolver(
    private val repository: TemplateRepository,
    private val paddleboatImporter: PaddleboatTemplateImporter,
    private val bundledRetroArchEntries: List<RetroArchCfgEntry>,
    private val templatesDir: File? = null,
) {

    fun resolve(device: ConnectedDevice): ResolvedTemplate {
        val matchInput = device.toMatchInput()

        val candidates = repository.list()
            .map { it to it.match.score(matchInput) }
            .filter { (_, score) -> score > 0 }
        if (candidates.isNotEmpty()) {
            val best = candidates.maxWithOrNull(
                compareBy<Pair<DeviceTemplate, Int>> { (_, score) -> score }
                    .thenBy { (template, _) ->
                        templatesDir?.let { dir -> File(dir, "${template.id}.ini").lastModified() } ?: 0L
                    }
            )
            if (best != null) return ResolvedTemplate(best.first, persistent = true)
        }

        paddleboatImporter.importFor(device)?.let { template ->
            repository.save(template)
            return ResolvedTemplate(template, persistent = true)
        }

        val raMatch = bestRetroArchEntry(device)
        if (raMatch != null) {
            val template = RetroArchAutoconfigImporter.import(raMatch, device)
            repository.save(template)
            return ResolvedTemplate(template, persistent = true)
        }

        val fallback = AndroidDefaultTemplateFactory.create(device)
        return ResolvedTemplate(fallback, persistent = false)
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
