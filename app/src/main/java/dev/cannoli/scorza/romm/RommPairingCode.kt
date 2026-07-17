package dev.cannoli.scorza.romm

object RommPairingCode {
    private val ALLOWED = Regex("^[A-Z0-9]{8}$")

    fun normalize(raw: String): String =
        raw.uppercase().filter { !it.isWhitespace() && it != '-' }

    fun isValid(raw: String): Boolean = ALLOWED.matches(normalize(raw))
}
