package dev.cannoli.scorza.db

sealed interface LibraryRef {
    val id: Long

    data class Rom(override val id: Long) : LibraryRef
    data class App(override val id: Long) : LibraryRef
}
