package dev.cannoli.scorza.romm

class PlatformMap(
    private val slugMap: RommSlugMap,
    private val isSupported: (tag: String) -> Boolean,
) {
    fun toDomain(dtos: List<PlatformDto>): List<RommPlatform> =
        dtos.mapNotNull { dto ->
            val tag = slugMap.tagForSlug(dto.slug) ?: return@mapNotNull null
            if (!isSupported(tag)) return@mapNotNull null
            RommPlatform(
                id = dto.id,
                slug = dto.slug,
                cannoliTag = tag,
                displayName = dto.displayName.ifEmpty { dto.name },
                romCount = dto.romCount,
            )
        }
}
