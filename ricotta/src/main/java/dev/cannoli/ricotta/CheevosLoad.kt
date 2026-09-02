package dev.cannoli.ricotta

/**
 * What became of this game's achievements, as the native side found it.
 *
 * Facts rather than a sentence, so the wording and the language stay on this side. [who] is empty
 * when nobody is logged in, which is why the loaded message can still be shown without a name.
 */
data class CheevosLoad(
    val outcome: Outcome,
    val unlocked: Int,
    val total: Int,
    val hardcore: Boolean,
    val who: String,
) {
    enum class Outcome { LOADED, UNRECOGNISED, NO_ACHIEVEMENTS, UNAVAILABLE }

    companion object {
        // Split with a limit so a display name carrying the delimiter cannot eat the fields after it.
        fun parse(payload: String): CheevosLoad? {
            val f = payload.split("|", limit = 5)
            if (f.size < 5) return null
            val outcome = Outcome.entries.getOrNull(f[0].toIntOrNull() ?: return null) ?: return null
            return CheevosLoad(
                outcome = outcome,
                unlocked = f[1].toIntOrNull() ?: 0,
                total = f[2].toIntOrNull() ?: 0,
                hardcore = f[3] == "1",
                who = f[4],
            )
        }
    }
}
