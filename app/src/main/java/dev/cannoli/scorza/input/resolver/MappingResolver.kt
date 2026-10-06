package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping

class MappingResolver(
    private val diskRepository: AutoconfigRepository,
    private val bundledRetroArchEntries: BundledAutoconfigEntries,
    private val genericControllerName: String = "Generic Controller",
) {

    fun resolve(device: ConnectedDevice): DeviceMapping {
        val disk = diskRepository.listEntries()
        val candidates = diskRepository.stagedEntries() + disk.ifEmpty { bundledRetroArchEntries.entries() }

        val best = candidates
            .mapNotNull { entry -> IdentityMatcher.rank(entry, device)?.let { entry to it } }
            .minWithOrNull(
                compareBy<Pair<RetroArchCfgEntry, MatchRank>> {
                    if (it.first.isUserOwned) 0 else 1
                }
                    .thenBy { it.second.ordinal }
                    .thenBy { if (IdentityMatcher.builtinAgrees(it.first, device)) 0 else 1 }
                    .thenBy { it.first.fileName.orEmpty() }
            )
            ?.first

        return best?.let { RetroArchAutoconfigImporter.import(it, device) }
            ?: unidentifiedMapping(device, genericControllerName)
    }
}
