package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host that answers like RetroArch: values live in a map, and each key can be told to refuse a
 * write, clamp it, or refuse to be put back.
 */
private class SweepHost(
    initial: Map<String, String>,
    private val type: Map<String, RaSettingType> = emptyMap(),
    private val refuse: Set<String> = emptySet(),
    private val clampTo: Map<String, String> = emptyMap(),
    private val refuseRestore: Set<String> = emptySet(),
    private val screens: Map<String, List<RaScreenRow>> = emptyMap(),
) : RaSettingsHost {
    private val values = initial.toMutableMap()
    private val original = initial.toMap()
    val writes = mutableListOf<Pair<String, String>>()

    override fun raGetSetting(key: String): RaSetting? {
        val v = values[key] ?: return null
        return RaSetting(
            key = key,
            label = key,
            type = type[key] ?: RaSettingType.BOOL,
            machineValue = MachineValue(v),
            displayValue = v,
        )
    }

    override fun raApply(key: String, value: MachineValue, watch: Collection<String>): RaApplyResult? {
        writes += key to value.raw
        val held = values[key] ?: return null
        if (key in refuse) return RaApplyResult(MachineValue(held))
        // A restore is the write that puts the original back, and a host that clamps an
        // out-of-range value does not clamp the value it already held.
        val restoring = value.raw == original[key]
        if (restoring && key in refuseRestore) return RaApplyResult(MachineValue(held))
        values[key] = if (restoring) value.raw else clampTo[key] ?: value.raw
        return RaApplyResult(MachineValue(values.getValue(key)))
    }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {}
    override fun raScreenRows(label: String): List<RaScreenRow> = screens[label].orEmpty()
}

class RaSettingsSweepTest {

    private fun sweep(host: RaSettingsHost) = RaSettingsSweep(host)

    @Test fun `a setting that takes the write and goes back is the good case`() {
        val host = SweepHost(mapOf("a" to "false"))
        val report = sweep(host).run(listOf("a"))
        assertEquals(RaSettingsSweep.Outcome.STUCK, report.rows.single().outcome)
        assertEquals("false", host.raGetSetting("a")?.machineValue?.raw)
    }

    // The case this whole sweep exists to count: the menu asked, the write went nowhere, and only
    // a re-read would ever have shown it.
    @Test fun `a write the host drops is reported as refused`() {
        val host = SweepHost(mapOf("a" to "false"), refuse = setOf("a"))
        val row = sweep(host).run(listOf("a")).rows.single()
        assertEquals(RaSettingsSweep.Outcome.REFUSED, row.outcome)
        assertEquals("false", row.from)
        assertEquals("true", row.asked)
        assertEquals("false", row.got)
    }

    @Test fun `a write the host rewrites is reported as clamped, with what it chose`() {
        val host = SweepHost(
            mapOf("n" to "3"),
            type = mapOf("n" to RaSettingType.INT),
            clampTo = mapOf("n" to "9"),
        )
        val row = sweep(host).run(listOf("n")).rows.single()
        assertEquals(RaSettingsSweep.Outcome.CLAMPED, row.outcome)
        assertEquals("9", row.got)
    }

    // Worse than a refusal: the sweep changed the running game and could not undo it.
    @Test fun `a value that cannot be put back is called out`() {
        val host = SweepHost(mapOf("a" to "false"), refuseRestore = setOf("a"))
        val row = sweep(host).run(listOf("a")).rows.single()
        assertEquals(RaSettingsSweep.Outcome.RESTORE_FAILED, row.outcome)
    }

    // Stepping a float and formatting it back produces a different string for the same number.
    // Called a clamp, it is the sweep reporting a lie of exactly the kind it exists to catch.
    @Test fun `a float that only changed spelling is not a clamp`() {
        // Asked for 55.5 and told "55.500000": the same number, and on a device the same thing
        // happened the other way round, asking 54.599998 and being told 54.6.
        val host = SweepHost(
            mapOf("f" to "54.5"),
            type = mapOf("f" to RaSettingType.FLOAT),
            clampTo = mapOf("f" to "55.500000"),
        )
        assertEquals(RaSettingsSweep.Outcome.STUCK, sweep(host).run(listOf("f")).rows.single().outcome)
    }

    @Test fun `a float RetroArch really did clamp is still reported`() {
        val host = SweepHost(
            mapOf("f" to "54.5"),
            type = mapOf("f" to RaSettingType.FLOAT),
            clampTo = mapOf("f" to "50.0"),
        )
        assertEquals(RaSettingsSweep.Outcome.CLAMPED, sweep(host).run(listOf("f")).rows.single().outcome)
    }

    // The menu refuses these rows, so a sweep that tests them is not testing what a player can
    // reach. Sixteen audio mixer streams were reported as missing settings on that basis.
    @Test fun `a hidden screen and a hidden key are never walked`() {
        val hiddenScreen = HIDDEN_SCREENS.first()
        val hiddenKey = HIDDEN_KEYS.first()
        val host = SweepHost(
            mapOf("a" to "false"),
            screens = mapOf(
                "" to listOf(
                    RaScreenRow(hiddenScreen, "Hidden", isMenu = true),
                    RaScreenRow(hiddenKey, "Hidden Key", isMenu = false),
                    RaScreenRow("a", "A", isMenu = false),
                ),
                hiddenScreen to listOf(RaScreenRow("buried", "Buried", isMenu = false)),
            ),
        )
        assertEquals(listOf("a"), RaSettingsSweep.discoverKeys(host))
    }

    @Test fun `a read-only setting is skipped rather than counted as a failure`() {
        val host = SweepHost(mapOf("p" to "/roms"), type = mapOf("p" to RaSettingType.STRING_RO))
        assertEquals(RaSettingsSweep.Outcome.SKIPPED_UNCHANGEABLE, sweep(host).run(listOf("p")).rows.single().outcome)
    }

    @Test fun `a key the host does not have is reported rather than skipped silently`() {
        assertEquals(RaSettingsSweep.Outcome.MISSING, sweep(SweepHost(emptyMap())).run(listOf("nope")).rows.single().outcome)
    }

    // A write with no answer is not a refusal: the emulator ran out of time to say what it did,
    // and reporting that as a lying row would be the sweep itself lying.
    @Test fun `a write the host never answers is its own outcome`() {
        val host = object : RaSettingsHost by SweepHost(mapOf("a" to "false")) {
            override fun raApply(key: String, value: MachineValue, watch: Collection<String>): RaApplyResult? = null
        }
        assertEquals(RaSettingsSweep.Outcome.UNANSWERED, sweep(host).run(listOf("a")).rows.single().outcome)
    }

    // Writing a driver mid-session would take the video or audio away and never give it back, so
    // the sweep says it did not test them rather than testing them.
    @Test fun `drivers are never written, and the report says so`() {
        val host = SweepHost(mapOf("video_driver" to "gl", "audio_driver" to "opensl"))
        val report = sweep(host).run(listOf("video_driver", "audio_driver"))
        assertTrue(report.rows.all { it.outcome == RaSettingsSweep.Outcome.SKIPPED_UNSAFE })
        assertTrue(host.writes.isEmpty())
    }

    @Test fun `the account keys are never touched`() {
        val host = SweepHost(mapOf("cheevos_token" to "abc", "cheevos_hardcore_mode_enable" to "false"))
        val report = sweep(host).run(listOf("cheevos_token", "cheevos_hardcore_mode_enable"))
        assertTrue(report.rows.all { it.outcome == RaSettingsSweep.Outcome.SKIPPED_UNSAFE })
        assertTrue(host.writes.isEmpty())
    }

    @Test fun `the summary counts what matters and the text names the failures`() {
        val host = SweepHost(
            mapOf("ok" to "false", "bad" to "false", "ro" to "x"),
            type = mapOf("ro" to RaSettingType.STRING_RO),
            refuse = setOf("bad"),
        )
        val report = sweep(host).run(listOf("ok", "bad", "ro"))
        assertEquals(1, report.count(RaSettingsSweep.Outcome.STUCK))
        assertEquals(1, report.count(RaSettingsSweep.Outcome.REFUSED))
        assertEquals(1, report.count(RaSettingsSweep.Outcome.SKIPPED_UNCHANGEABLE))
        assertTrue(report.text().contains("bad"))
        assertTrue(report.text().contains("REFUSED"))
    }

    // The universe is whatever the menu can reach, walked the way All Settings walks it, so the
    // sweep covers exactly what v2 exposes rather than a list written by hand.
    @Test fun `the keys are discovered by walking the screens the menu shows`() {
        val host = SweepHost(
            mapOf("a" to "false", "b" to "false"),
            screens = mapOf(
                "" to listOf(RaScreenRow("video", "Video", isMenu = true), RaScreenRow("a", "A", isMenu = false)),
                "video" to listOf(RaScreenRow("b", "B", isMenu = false)),
            ),
        )
        // Depth first in display order: a submenu's rows belong where the submenu sits.
        assertEquals(listOf("b", "a"), RaSettingsSweep.discoverKeys(host))
    }

    @Test fun `a screen that lists itself does not spin forever`() {
        val host = SweepHost(
            mapOf("a" to "false"),
            screens = mapOf(
                "" to listOf(RaScreenRow("loop", "Loop", isMenu = true)),
                "loop" to listOf(RaScreenRow("loop", "Loop", isMenu = true), RaScreenRow("a", "A", isMenu = false)),
            ),
        )
        assertEquals(listOf("a"), RaSettingsSweep.discoverKeys(host))
    }
}
