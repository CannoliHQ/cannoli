package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Writes every setting the menu can reach and puts it back as it was, counting what RetroArch did
 * with each write.
 *
 * The write path into RetroArch has no automated verification: the Kotlin tests run against a fake
 * bridge, and the natives are checked by hand, which is why every defect in it so far was found by
 * somebody using the menu and noticing a row that lied. This turns that into a count. It asks the
 * same question the menu asks, through the same host and the same value cycler, so a row it reports
 * as refused is a row a player would have seen do nothing.
 *
 * It changes the running game while it runs, one setting at a time, and puts each back before
 * moving on. A value it cannot put back is the worst outcome here and is reported as such.
 */
class RaSettingsSweep(
    private val host: RaSettingsHost,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
) {
    enum class Outcome {
        /** Written, read back as asked, and put back. What every row should be. */
        STUCK,

        /** The write reached the host and the value did not move. */
        REFUSED,

        /** The host had the key and never answered for it, so nothing is known about the write. */
        UNANSWERED,

        /** The host took a write and chose a different value, which the menu also would not show. */
        CLAMPED,

        /** Changed and could not be returned to what it was. The session is now different. */
        RESTORE_FAILED,

        /** Read only, or a choice with nothing else to choose. Nothing to test. */
        SKIPPED_UNCHANGEABLE,

        /** Deliberately not written: see [unsafe]. */
        SKIPPED_UNSAFE,

        /** The menu lists it and the host does not have it. */
        MISSING,
    }

    data class Row(
        val key: String,
        val outcome: Outcome,
        val from: String? = null,
        val asked: String? = null,
        val got: String? = null,
    )

    data class Report(val rows: List<Row>) {
        fun count(outcome: Outcome): Int = rows.count { it.outcome == outcome }

        /**
         * The summary first, then every row that is not [Outcome.STUCK]. A list of what worked is
         * the part nobody reads, and the counts already carry it.
         */
        fun text(): String = buildString {
            append("settings sweep: ${rows.size} keys\n")
            for (o in Outcome.entries) append("  ${o.name.lowercase()}: ${count(o)}\n")
            val notable = rows.filter { it.outcome != Outcome.STUCK }
            if (notable.isEmpty()) {
                append("\nevery key the menu can reach took a write and went back.\n")
                return@buildString
            }
            append("\n")
            for (r in notable) {
                append(r.outcome.name.padEnd(21))
                append(r.key)
                if (r.asked != null) append("  from=${r.from} asked=${r.asked} got=${r.got}")
                append("\n")
            }
        }
    }

    fun run(keys: List<String>): Report = Report(keys.map(::probe)).also { host.flushHeldCommands() }

    // The sweep runs on its own thread and the answer arrives on the main one, so it can wait. The
    // menu never does.
    private fun apply(key: String, value: MachineValue): MachineValue? {
        val answered = CountDownLatch(1)
        var answer: RaApplyResult? = null
        val queued = host.raApply(key, value) {
            answer = it
            answered.countDown()
        }
        if (!queued || !answered.await(answerTimeoutMs, TimeUnit.MILLISECONDS)) return null
        return answer?.value
    }

    private fun probe(key: String): Row {
        if (unsafe(key)) return Row(key, Outcome.SKIPPED_UNSAFE)
        val before = host.raGetSetting(key) ?: return Row(key, Outcome.MISSING)
        val from = before.machineValue
        val asked = RaValueCycler.next(before, 1)?.takeIf { it != from }
            ?: return Row(key, Outcome.SKIPPED_UNCHANGEABLE)

        val got = apply(key, asked)
            ?: return Row(key, Outcome.UNANSWERED, from.raw, asked.raw, null)

        val outcome = when {
            same(before.type, got, asked) -> Outcome.STUCK
            same(before.type, got, from) -> Outcome.REFUSED
            else -> Outcome.CLAMPED
        }
        if (outcome == Outcome.REFUSED) return Row(key, outcome, from.raw, asked.raw, got.raw)

        val restored = apply(key, from)
        if (!same(before.type, restored, from)) {
            return Row(key, Outcome.RESTORE_FAILED, from.raw, asked.raw, restored?.raw)
        }
        return Row(key, outcome, from.raw, asked.raw, got.raw)
    }

    /**
     * Whether two machine values are the same setting value, not the same string.
     *
     * A float survives a round trip through the cycler's arithmetic and RetroArch's formatting as
     * a different string for the same number: stepping 54.5 by 0.1 asks for 54.599998 and reads
     * back 54.6. Comparing the text called that a clamp RetroArch never performed, which is the
     * sweep telling the exact kind of lie it exists to catch.
     */
    private fun same(type: RaSettingType, a: MachineValue?, b: MachineValue?): Boolean {
        if (a == b) return true
        if (type != RaSettingType.FLOAT) return false
        val x = a?.raw?.toFloatOrNull() ?: return false
        val y = b?.raw?.toFloatOrNull() ?: return false
        return kotlin.math.abs(x - y) <= 1e-5f * maxOf(1f, kotlin.math.abs(x), kotlin.math.abs(y))
    }

    /**
     * Writing a driver takes the video, audio or input away mid-session and nothing gives it back,
     * and the account keys are credentials rather than preferences. Both are reported as untested
     * rather than quietly left out of the list.
     */
    private fun unsafe(key: String): Boolean =
        key.endsWith("_driver") || key in CheevosSessionKeys.ALL

    companion object {
        private const val ANSWER_TIMEOUT_MS = 10_000L

        /**
         * Every key the menu can reach, walked the way All Settings walks it, so the sweep covers
         * what v2 actually exposes rather than a list somebody kept up to date by hand.
         *
         * Hidden screens and keys are skipped for the same reason: a row the menu refuses is not a
         * row a player can reach. Walking past them reported sixteen audio mixer streams as
         * missing settings, which they are, but the menu never offered them in the first place.
         */
        fun discoverKeys(host: RaSettingsHost, maxDepth: Int = 8): List<String> {
            val keys = LinkedHashSet<String>()
            val visited = mutableSetOf<String>()
            fun walk(label: String, depth: Int) {
                if (depth > maxDepth || !visited.add(label)) return
                for (row in host.raScreenRows(label)) {
                    if (row.key in HIDDEN_SCREENS || row.key in HIDDEN_KEYS) continue
                    if (row.isMenu) walk(row.key, depth + 1) else keys += row.key
                }
            }
            walk("", 0)
            return keys.toList()
        }
    }
}
