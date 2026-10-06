package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.EmulatorChoice

object CoreUsage {

    data class Row(
        val coreId: String,
        val displayName: String,
        val sizeBytes: Long,
        val usedBy: List<String>,
    ) {
        val inUse: Boolean get() = usedBy.isNotEmpty()
    }

    fun usedCoreIds(
        platformTags: Collection<String>,
        coreMappingFor: (String) -> String,
        overridesFor: (String) -> List<EmulatorChoice>,
    ): Set<String> = buildSet {
        for (tag in platformTags) {
            coreMappingFor(tag).takeIf { it.isNotBlank() }?.let { add(it) }
            for (choice in overridesFor(tag)) {
                choice.coreId?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    fun rows(
        installed: Collection<String>,
        sizeOf: (String) -> Long,
        displayNameOf: (String) -> String,
        usedBy: (String) -> List<String>,
    ): List<Row> = installed
        .map { Row(it, displayNameOf(it), sizeOf(it), usedBy(it)) }
        .sortedWith(compareBy({ !it.inUse }, { it.displayName.lowercase() }))

    fun reclaimableBytes(rows: List<Row>): Long = rows.filterNot { it.inUse }.sumOf { it.sizeBytes }
}
