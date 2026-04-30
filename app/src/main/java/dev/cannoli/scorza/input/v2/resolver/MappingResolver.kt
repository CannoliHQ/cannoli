package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.repo.TemplateRepository
import dev.cannoli.ui.ConfirmButton
import java.io.File

data class ResolvedTemplate(
    val template: DeviceTemplate,
    val persistent: Boolean,
)

class TemplateResolver(
    private val repository: TemplateRepository,
    private val bundledRetroArchEntries: List<RetroArchCfgEntry>,
    private val menuConvention: () -> ConfirmButton,
    private val templatesDir: File? = null,
) {

    fun resolve(device: ConnectedDevice): ResolvedTemplate {
        val matchInput = device.toMatchInput()

        val candidates = repository.list()
            .map { it to it.match.score(matchInput) }
            .filter { it.second > 0 }
        if (candidates.isNotEmpty()) {
            val best = candidates.maxWithOrNull(
                compareBy<Pair<DeviceTemplate, Int>>({ it.second })
                    .thenBy { templatesDir?.let { dir -> File(dir, "${it.first.id}.ini").lastModified() } ?: 0L }
            )
            if (best != null) {
                val template = if (best.first.userEdited) best.first else applyMenuConvention(best.first)
                return ResolvedTemplate(template, persistent = true)
            }
        }

        val raMatch = bestRetroArchEntry(device)
        if (raMatch != null) {
            val template = applyMenuConvention(RetroArchAutoconfigImporter.import(raMatch, device))
            return ResolvedTemplate(template, persistent = false)
        }

        val fallback = applyMenuConvention(AndroidDefaultTemplateFactory.create(device))
        return ResolvedTemplate(fallback, persistent = false)
    }

    private fun applyMenuConvention(template: DeviceTemplate): DeviceTemplate {
        return when (menuConvention()) {
            ConfirmButton.EAST -> template.copy(
                menuConfirm = CanonicalButton.BTN_EAST,
                menuBack = CanonicalButton.BTN_SOUTH,
            )
            ConfirmButton.SOUTH -> template.copy(
                menuConfirm = CanonicalButton.BTN_SOUTH,
                menuBack = CanonicalButton.BTN_EAST,
            )
        }
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
