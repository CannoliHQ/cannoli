package dev.cannoli.scorza.config

/**
 * Curated cores with no armeabi-v7a build on the buildbot, so CoreInfoRepository hides them from a
 * 32-bit device rather than offering an option that cannot run there. CuratedCatalogueTest asserts
 * every member here really is arm64-only and is in curated-cores.txt, so this cannot be used to
 * hide a core that simply lost a build.
 */
object Arm64OnlyCores {
    val IDS: Set<String> = setOf("armsx2_libretro")
}
