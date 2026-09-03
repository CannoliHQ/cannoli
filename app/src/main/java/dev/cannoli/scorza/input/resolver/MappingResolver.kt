package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping

class MappingResolver(
    private val diskRepository: AutoconfigRepository,
    private val bundledRetroArchEntries: BundledAutoconfigEntries,
    /** Shown for a pad that reports no name of its own. Passed in because this has no Context. */
    private val genericControllerName: String = "Generic Controller",
) {

    /**
     * Resolve a connected device to its mapping.
     */
    fun resolve(device: ConnectedDevice): DeviceMapping {
        val disk = diskRepository.listEntries()
        // Assets cover the window before seeding lands or while storage is unreadable. A non-empty
        // disk set is the seeded database and answers for everything: falling back to assets then
        // would resurrect profiles the seeder deliberately did not materialise for this handheld.
        //
        // Staged cfgs sit outside that rule. They are the one pad the user configured before first
        // run chose a card, not a seeded database, so they join the candidates without silencing
        // the assets for every other pad.
        val candidates = diskRepository.stagedEntries() + disk.ifEmpty { bundledRetroArchEntries.entries() }

        val best = candidates
            .mapNotNull { entry -> IdentityMatcher.rank(entry, device)?.let { entry to it } }
            .minWithOrNull(
                compareBy<Pair<RetroArchCfgEntry, MatchRank>> {
                    if (it.first.isUserOwned) 0 else 1
                }
                    .thenBy { it.second.ordinal }
                    .thenBy { if (IdentityMatcher.builtinAgrees(it.first, device)) 0 else 1 }
                    // A directory listing has no order of its own, so break remaining ties on the
                    // filename rather than letting the filesystem decide a pad's layout.
                    .thenBy { it.first.fileName.orEmpty() }
            )
            ?.first

        return best?.let { RetroArchAutoconfigImporter.import(it, device) }
            ?: unidentifiedMapping(device, genericControllerName)
    }
}
