package dev.cannoli.scorza.saves

object SharedSaveRoots {
    private val ROOTS = mapOf("PSP" to "SAVEDATA")

    fun subdirFor(tag: String): String? = ROOTS[tag.uppercase()]

    fun isShared(tag: String): Boolean = subdirFor(tag) != null
}
