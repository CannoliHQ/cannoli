package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.EmulatorChoice

/**
 * Which installed cores something still points at.
 *
 * This decides what a bulk removal deletes, so it is deliberately generous. A core kept when it
 * could have gone costs a few megabytes; a core deleted while a platform still names it costs the
 * user a download and a launch that fails in between. Every rule here errs the same way.
 */
object CoreUsage {

    data class Row(
        val coreId: String,
        val displayName: String,
        val sizeBytes: Long,
        /** Platforms naming this core, already display names. Empty means nothing points at it. */
        val usedBy: List<String>,
    ) {
        val inUse: Boolean get() = usedBy.isNotEmpty()
    }

    /**
     * [coreMappingFor] must be PlatformConfig.getCoreMapping, which already folds in the explicit
     * pick, the ini and the built-in default. Reading userChoices alone would miss a platform
     * running its default core, which is the common case for anyone who never opened the picker.
     *
     * A core named by a choice that is not Embedded still counts. The core is not ours to run in
     * that case, but the user picked something that names it, and a bulk delete is the wrong place
     * to be clever about the distinction.
     */
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

    /** Installed cores, each with what points at it, ordered with the removable ones last. */
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
