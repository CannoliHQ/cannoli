package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SCREEN = "async_screen"

private class DeferredHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    private val rows = mutableListOf<RaScreenRow>()
    private val pending = ArrayDeque<() -> Unit>()
    val clampTo = mutableMapOf<String, MachineValue>()
    val refuse = mutableSetOf<String>()
    val moves = mutableMapOf<String, Pair<String, MachineValue>>()
    val saved = mutableListOf<Set<String>>()

    fun put(key: String, value: String, vararg choices: String) {
        rows += RaScreenRow(key, key, isMenu = false)
        settings[key] = RaSetting(
            key = key,
            label = key,
            type = RaSettingType.ENUM,
            machineValue = MachineValue(value),
            displayValue = value.uppercase(),
            options = choices.map { RaOption(MachineValue(it), it.uppercase()) },
        )
    }

    fun queued() = pending.size

    fun drain() {
        while (pending.isNotEmpty()) pending.removeFirst()()
    }

    private fun set(key: String, v: MachineValue) {
        settings[key] = settings.getValue(key).copy(machineValue = v, displayValue = v.raw.uppercase())
    }

    override fun raGetSetting(key: String): RaSetting? = settings[key]

    override fun raScreenRows(label: String): List<RaScreenRow> = when (label) {
        "" -> listOf(RaScreenRow(SCREEN, "Screen", isMenu = true))
        SCREEN -> rows
        else -> emptyList()
    }

    override fun raApply(
        key: String,
        value: MachineValue,
        watch: Collection<String>,
        onDone: (RaApplyResult?) -> Unit,
    ): Boolean {
        if (key !in settings) return false
        pending.addLast {
            val landed = when (key) {
                in refuse -> settings.getValue(key).machineValue
                else -> clampTo[key] ?: value
            }
            set(key, landed)
            val moved = mutableMapOf<String, MachineValue>()
            moves[key]?.let { (neighbour, to) ->
                if (neighbour in watch) moved[neighbour] = settings.getValue(neighbour).machineValue
                set(neighbour, to)
            }
            onDone(RaApplyResult(landed, moved))
        }
        return true
    }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) { saved += keys }
}

class RaIgmSettingsAsyncApplyTest {

    private fun provider(host: DeferredHost) =
        RaIgmSettingsProvider(host = host, strings = RaOptionStrings(), curated = false)

    private fun shown(p: RaIgmSettingsProvider, key: String): String =
        p.screen(listOf(SCREEN)).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>()
            .single { it.key == key }.value

    @Test fun `a press shows the asked value before the answer and RetroArch's after it`() {
        val host = DeferredHost().apply {
            put("k", "a", "a", "b", "c")
            clampTo["k"] = MachineValue("c")
        }
        val p = provider(host)
        p.screen(listOf(SCREEN))

        p.cycle("k", 1)
        assertEquals("B", shown(p, "k"))

        host.drain()
        assertEquals("C", shown(p, "k"))
    }

    @Test fun `a refused write shows what RetroArch kept once it answers`() {
        val host = DeferredHost().apply {
            put("k", "a", "a", "b")
            refuse += "k"
        }
        val p = provider(host)
        p.screen(listOf(SCREEN))

        p.cycle("k", 1)
        host.drain()

        assertEquals("A", shown(p, "k"))
    }

    @Test fun `two presses before the first answer step twice`() {
        val host = DeferredHost().apply { put("k", "a", "a", "b", "c") }
        val p = provider(host)
        p.screen(listOf(SCREEN))

        p.cycle("k", 1)
        p.cycle("k", 1)
        assertEquals(2, host.queued())

        host.drain()
        assertEquals("c", host.settings.getValue("k").machineValue.raw)
        assertEquals("C", shown(p, "k"))
    }

    @Test fun `a press is a change before the answer arrives`() {
        val host = DeferredHost().apply { put("k", "a", "a", "b") }
        val p = provider(host)
        p.screen(listOf(SCREEN))

        p.cycle("k", 1)

        assertTrue(p.exitPrompt() is IgmSettingsExit.Prompt)
    }

    @Test fun `discard restores a neighbour the answer reported as moved`() {
        val host = DeferredHost().apply {
            put("bfi", "0", "0", "1")
            put("swap", "1", "0", "1")
            moves["bfi"] = "swap" to MachineValue("0")
        }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("bfi", 1)
        host.drain()
        assertEquals("0", host.settings.getValue("swap").machineValue.raw)

        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.discard)
        host.drain()

        assertEquals("0", host.settings.getValue("bfi").machineValue.raw)
        assertEquals("1", host.settings.getValue("swap").machineValue.raw)
    }

    @Test fun `an answer that lands after a save is not put back by the next discard`() {
        val host = DeferredHost().apply {
            put("bfi", "0", "0", "1")
            put("swap", "1", "0", "1")
            put("other", "x", "x", "y")
            moves["bfi"] = "swap" to MachineValue("0")
        }
        val p = provider(host)
        p.screen(listOf(SCREEN))
        p.cycle("bfi", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.platform)
        host.drain()

        p.cycle("other", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.discard)
        host.drain()

        assertEquals("0", host.settings.getValue("swap").machineValue.raw)
    }

    @Test fun `a write the host cannot queue is not a change`() {
        val host = object : RaSettingsHost by (DeferredHost().apply { put("k", "a", "a", "b") }) {
            override fun raApply(
                key: String,
                value: MachineValue,
                watch: Collection<String>,
                onDone: (RaApplyResult?) -> Unit,
            ): Boolean = false
        }
        val p = RaIgmSettingsProvider(host = host, strings = RaOptionStrings(), curated = false)
        p.screen(listOf(SCREEN))

        p.cycle("k", 1)

        assertFalse(p.exitPrompt() is IgmSettingsExit.Prompt)
    }
}
