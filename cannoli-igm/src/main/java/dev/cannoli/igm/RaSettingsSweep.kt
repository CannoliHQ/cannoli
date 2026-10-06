package dev.cannoli.igm

import dev.cannoli.core.CheevosSessionKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RaSettingsSweep(
    private val host: RaSettingsHost,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
) {
    enum class Outcome {
        STUCK,

        REFUSED,

        UNANSWERED,

        CLAMPED,

        RESTORE_FAILED,

        SKIPPED_UNCHANGEABLE,

        SKIPPED_UNSAFE,

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

    private fun same(type: RaSettingType, a: MachineValue?, b: MachineValue?): Boolean {
        if (a == b) return true
        if (type != RaSettingType.FLOAT) return false
        val x = a?.raw?.toFloatOrNull() ?: return false
        val y = b?.raw?.toFloatOrNull() ?: return false
        return kotlin.math.abs(x - y) <= 1e-5f * maxOf(1f, kotlin.math.abs(x), kotlin.math.abs(y))
    }

    private fun unsafe(key: String): Boolean =
        key.endsWith("_driver") || key in CheevosSessionKeys.ALL

    companion object {
        private const val ANSWER_TIMEOUT_MS = 10_000L

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
