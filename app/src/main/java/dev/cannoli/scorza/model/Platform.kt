package dev.cannoli.scorza.model

data class Platform(
    val tag: String,
    val displayName: String,
    val coreName: String?,
    val gameCount: Int = 0,
    val tags: List<String> = emptyList()
) {
    val allTags: List<String> get() = tags.ifEmpty { listOf(tag) }
}
