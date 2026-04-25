package dev.cannoli.scorza.library

sealed interface LibraryRef {
    data class Rom(val id: Long) : LibraryRef
    data class App(val id: Long) : LibraryRef
}
