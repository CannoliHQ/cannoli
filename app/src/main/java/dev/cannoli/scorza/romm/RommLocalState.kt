package dev.cannoli.scorza.romm

enum class LocalState { PRESENT, REMOTE }

data class LocalFile(val name: String, val sizeBytes: Long)

object RommLocalState {
    fun of(fsName: String, sizeBytes: Long, localFiles: List<LocalFile>): LocalState {
        val match = localFiles.any { it.name.equals(fsName, ignoreCase = true) && it.sizeBytes == sizeBytes }
        return if (match) LocalState.PRESENT else LocalState.REMOTE
    }
}
