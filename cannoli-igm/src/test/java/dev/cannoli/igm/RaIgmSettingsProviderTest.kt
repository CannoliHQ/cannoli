package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRaHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    val setCalls = mutableListOf<Pair<String, String>>()
    val savedScopes = mutableListOf<RaOverrideScope>()
    val savedKeys = mutableListOf<Set<String>>()
    val screens = mutableMapOf<String, List<RaScreenRow>>()

    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raScreenRows(label: String): List<RaScreenRow> = screens[label].orEmpty()
    var setSucceeds = true

    var changeHandler: ((String) -> Unit)? = null

    fun applyNow(key: String, value: MachineValue, watch: Collection<String>): RaApplyResult? {
        setCalls.add(key to value.raw)
        if (!setSucceeds) return null
        val current = settings[key] ?: return null
        val before = watch.associateWith { settings[it]?.machineValue }
        settings[key] = current.copy(
            machineValue = value,
            displayValue = current.options?.firstOrNull { o -> o.machine == value }?.display ?: value.raw,
        )
        changeHandler?.invoke(key)
        val applied = settings[key]?.machineValue ?: return null
        return RaApplyResult(
            applied,
            watch.filter { settings[it]?.machineValue != before[it] }
                .mapNotNull { k -> before[k]?.let { k to it } }
                .toMap(),
        )
    }

    override fun raApply(
        key: String,
        value: MachineValue,
        watch: Collection<String>,
        onDone: (RaApplyResult?) -> Unit,
    ): Boolean = answerNow(onDone) { applyNow(key, value, watch) }

    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {
        savedScopes.add(scope)
        savedKeys.add(keys)
    }

    val presetFiles = mutableMapOf<String, String>()
    var appliedPreset: String? = null
    var chain: dev.cannoli.core.shader.ShaderPreset? = null
    val chainApplies = mutableListOf<String?>()

    override fun appliedShaderPreset(): String? = appliedPreset

    var shaderStashed: String? = null
    override fun shaderToRestore(): String? = shaderStashed ?: appliedPreset
    override fun toggleShader() {
        val live = appliedPreset
        if (live != null) {
            shaderStashed = live
            appliedPreset = null
        } else {
            appliedPreset = shaderStashed
        }
    }
    override fun shaderEntries(path: List<String>): List<dev.cannoli.core.shader.ShaderEntry> =
        listOf(dev.cannoli.core.shader.ShaderEntry("crt-geom", isFolder = false,
            path = "/Shaders/crt/crt-geom.slangp"))
    override fun shaderPresetPath(path: List<String>, name: String): String? =
        presetFiles[(path + name).joinToString("/")]
    override fun setShaderChain(chain: dev.cannoli.core.shader.ShaderPreset?) { this.chain = chain }
    override fun applyShaderChain(saveAs: String?): String? {
        chainApplies.add(saveAs)
        return "/Shaders/Custom/${saveAs ?: "working"}.slangp"
    }
    val applyCalls = mutableListOf<Pair<List<String>, String>>()
    override fun applyShaderPreset(path: List<String>, name: String): Set<String> {
        applyCalls.add(path to name)
        return setOf("cannoli_shader")
    }

    var shaderIsGameOwned = false
    var shaderRestored = 0
    override fun shaderOverriddenAtGame(): Boolean = shaderIsGameOwned
    override fun restoreShaderDefault(): Set<String> {
        shaderRestored++
        shaderIsGameOwned = false
        return setOf("cannoli_shader")
    }
}

private fun host(): FakeRaHost = FakeRaHost().apply {
    screens[""] = listOf(
        RaScreenRow("latency_settings", "Latency", isMenu = true),
        RaScreenRow("midi_settings", "MIDI", isMenu = true),
    )
    screens["latency_settings"] = listOf(
        RaScreenRow("run_ahead_frames", "Run-Ahead Frames", isMenu = false),
        RaScreenRow("run_ahead_hide_warnings", "Hide Run-Ahead Warnings", isMenu = false),
    )
    settings["run_ahead_frames"] =
        RaSetting("run_ahead_frames", "Run-Ahead Frames", RaSettingType.INT, MachineValue("1"), "1", min = 0f, max = 4f, step = 1f)
    settings["run_ahead_hide_warnings"] =
        RaSetting("run_ahead_hide_warnings", "Hide Run-Ahead Warnings", RaSettingType.BOOL, MachineValue("false"), "false")
}

private fun provider(
    h: FakeRaHost,
): RaIgmSettingsProvider =
    RaIgmSettingsProvider(host = h)

private val LATENCY = "latency_settings"

class RaIgmSettingsProviderTest {

    @Test
    fun `root mirrors RetroArch's own settings list, minus what Cannoli refuses`() {
        val p = provider(host())
        val items = p.screen(emptyList())
        val labels = items.items.map { it.label }
        assertEquals(listOf("Input", "Latency"), labels)
        assertTrue(items.items.all { it is GenericIgmSettingsItem.Category })
    }

    @Test
    fun `a category screen lists its settings as rows`() {
        val p = provider(host())
        val rows = p.screen(listOf(LATENCY)).items
        val bool = rows.filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.key == "run_ahead_hide_warnings" }
        assertEquals("Hide Run-Ahead Warnings", bool.label)
        assertEquals(RaOptionStrings().off, bool.value)
    }

    @Test
    fun `cycling an RA setting calls the host and flips the displayed value`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        assertEquals(listOf("run_ahead_hide_warnings" to "true"), h.setCalls)
        assertEquals(RaOptionStrings().on,
            p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()
                .first { it.key == "run_ahead_hide_warnings" }.value)
    }

    @Test
    fun `a row RetroArch reveals mid-screen appears`() {
        val h = host()
        h.settings["aspect_ratio_index"] =
            RaSetting("aspect_ratio_index", "Aspect Ratio", RaSettingType.INT, MachineValue("0"), "0", min = 0f, max = 24f, step = 1f)
        h.settings["video_aspect_ratio"] =
            RaSetting("video_aspect_ratio", "Config Aspect Ratio", RaSettingType.FLOAT, MachineValue("1.33"), "1.33")
        h.screens["video_scaling_settings"] = listOf(
            RaScreenRow("aspect_ratio_index", "Aspect Ratio", isMenu = false),
        )
        val p = provider(h)
        assertEquals(
            listOf("aspect_ratio_index"),
            p.screen(listOf("video_scaling_settings")).items.map { it.key },
        )

        h.screens["video_scaling_settings"] = listOf(
            RaScreenRow("aspect_ratio_index", "Aspect Ratio", isMenu = false),
            RaScreenRow("video_aspect_ratio", "Config Aspect Ratio", isMenu = false),
        )
        assertEquals(
            listOf("aspect_ratio_index", "video_aspect_ratio"),
            p.screen(listOf("video_scaling_settings")).items.map { it.key },
        )
    }

    @Test
    fun `revealing a row keeps the value just set on its neighbour`() {
        val h = host()
        h.screens["latency_settings"] = listOf(
            RaScreenRow("run_ahead_hide_warnings", "Hide Run-Ahead Warnings", isMenu = false),
        )
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)

        h.screens[LATENCY] = listOf(
            RaScreenRow("run_ahead_hide_warnings", "Hide Run-Ahead Warnings", isMenu = false),
            RaScreenRow("run_ahead_frames", "Run-Ahead Frames", isMenu = false),
        )
        val rows = p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()

        assertEquals(RaOptionStrings().on, rows.first { it.key == "run_ahead_hide_warnings" }.value)
        assertEquals(listOf("run_ahead_hide_warnings", "run_ahead_frames"), rows.map { it.key })
    }

    @Test
    fun `a value that moved behind the menu shows on the next visit to the screen`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        h.settings["run_ahead_frames"] = h.settings["run_ahead_frames"]!!
            .copy(machineValue = MachineValue("3"), displayValue = "3")

        p.screen(emptyList())
        assertEquals("3",
            p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()
                .first { it.key == "run_ahead_frames" }.value)
    }

    @Test
    fun `a cycle shows the text RetroArch renders, not the value it was asked for`() {
        val h = host()
        h.settings["aspect_ratio_index"] = RaSetting(
            key = "aspect_ratio_index",
            label = "Aspect Ratio",
            type = RaSettingType.ENUM,
            machineValue = MachineValue("21"),
            displayValue = "16:9",
            options = listOf(
                RaOption(MachineValue("21"), "16:9"),
                RaOption(MachineValue("22"), "Core Provided"),
            ),
        )
        h.screens[LATENCY] = listOf(RaScreenRow("aspect_ratio_index", "Aspect Ratio", isMenu = false))
        val p = provider(h)
        p.screen(listOf(LATENCY))

        p.cycle("aspect_ratio_index", 1)

        val row = p.screen(listOf(LATENCY)).items.filterIsInstance<GenericIgmSettingsItem.Choice>()
            .first { it.key == "aspect_ratio_index" }
        assertEquals("Core Provided", row.value)
        assertEquals(listOf("aspect_ratio_index" to "22"), h.setCalls)
    }

    @Test
    fun `a write that never queued leaves the menu clean`() {
        val h = host()
        h.setSucceeds = false
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)

        assertEquals(listOf("run_ahead_hide_warnings" to "true"), h.setCalls)
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `exit is clean when nothing changed`() {
        val p = provider(host())
        p.screen(listOf(LATENCY))
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `exit after a change prompts and each save scope routes to the host`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        val prompt = p.exitPrompt() as IgmSettingsExit.Prompt
        assertEquals(
            listOf(SaveAnswer.platform, SaveAnswer.game, SaveAnswer.discard),
            prompt.options.map { it.label },
        )
        prompt.choose(SaveAnswer.platform)
        assertEquals(listOf(RaOverrideScope.SYSTEM), h.savedScopes)
    }

    @Test
    fun `a save option keeps its action wherever it sits in the list`() {
        val expected = mapOf(
            SaveAnswer.platform to listOf(RaOverrideScope.SYSTEM),
            SaveAnswer.game to listOf(RaOverrideScope.GAME),
            SaveAnswer.discard to emptyList(),
        )
        for ((label, scopes) in expected) {
            val h = host()
            val p = provider(h)
            p.screen(listOf(LATENCY))
            p.cycle("run_ahead_hide_warnings", 1)

            val prompt = p.exitPrompt() as IgmSettingsExit.Prompt
            val reordered = prompt.copy(options = prompt.options.reversed())
            reordered.choose(label)

            assertEquals(label, scopes, h.savedScopes)
        }
    }

    @Test
    fun `exit after saving the game scope routes GAME`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.game)
        assertEquals(listOf(RaOverrideScope.GAME), h.savedScopes)
    }

    @Test
    fun `discarding on exit saves nothing and clears the dirty flag`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.discard)
        assertTrue(h.savedScopes.isEmpty())
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `saving writes exactly the changed RA keys and clears after`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        p.cycle("run_ahead_frames", 1)
        p.screen(emptyList())
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.game)
        assertEquals(listOf(setOf("run_ahead_hide_warnings", "run_ahead_frames")), h.savedKeys)

        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_frames", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.platform)
        assertEquals(setOf("run_ahead_frames"), h.savedKeys.last())
    }

    @Test
    fun `discarding clears the changed set so a later save carries nothing stale`() {
        val h = host()
        val p = provider(h)
        p.screen(listOf(LATENCY))
        p.cycle("run_ahead_hide_warnings", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.discard)
        p.cycle("run_ahead_frames", 1)
        (p.exitPrompt() as IgmSettingsExit.Prompt).choose(SaveAnswer.game)
        assertEquals(listOf(setOf("run_ahead_frames")), h.savedKeys)
    }

    @Test
    fun `a restart-required setting carries the restart hint`() {
        val h = host()
        h.settings["video_threaded"] =
            RaSetting("video_threaded", "Threaded Video", RaSettingType.BOOL, MachineValue("false"), "false", requiresRestart = true)
        val p = provider(h)
        h.screens["video_output_settings"] =
            listOf(RaScreenRow("video_threaded", "Threaded Video", isMenu = false))
        val row = p.screen(listOf("video_output_settings")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().firstOrNull { it.key == "video_threaded" }
        assertEquals(RaOptionStrings().restartHint, row?.hint)
    }

    @Test
    fun `dropping the game's shader is offered only where there is one to drop`() {
        val h = host()
        val p = provider(h)

        assertFalse(p.canRestoreDefault(listOf(CuratedCatalog.CATEGORY_SHADER)))
        h.shaderIsGameOwned = true
        assertTrue(p.canRestoreDefault(listOf(CuratedCatalog.CATEGORY_SHADER)))
        assertFalse(p.canRestoreDefault(listOf(LATENCY)))
        assertFalse(p.canRestoreDefault(emptyList()))
    }

    @Test
    fun `dropping it stages the key so the save prompt decides, and does nothing when not offered`() {
        val h = host()
        val p = provider(h)
        h.shaderIsGameOwned = true

        assertEquals(setOf("cannoli_shader"), p.restoreDefault(listOf(CuratedCatalog.CATEGORY_SHADER)))
        assertEquals(1, h.shaderRestored)
        assertTrue(p.exitPrompt() is IgmSettingsExit.Prompt)

        assertEquals(emptySet<String>(), p.restoreDefault(listOf(CuratedCatalog.CATEGORY_SHADER)))
        assertEquals(1, h.shaderRestored)
    }

    @Test
    fun `a level outside the shader tree never drops anything`() {
        val h = host()
        val p = provider(h)
        h.shaderIsGameOwned = true

        assertEquals(emptySet<String>(), p.restoreDefault(listOf(LATENCY)))
        assertEquals(0, h.shaderRestored)
    }

    @get:org.junit.Rule val shaderFolder = org.junit.rules.TemporaryFolder()

    private fun writePreset(name: String, vararg shaders: String): String {
        val dir = java.io.File(shaderFolder.root, "presets").apply { mkdirs() }
        val f = java.io.File(dir, "$name.slangp")
        f.writeText(buildString {
            append("shaders = ${shaders.size}\n")
            shaders.forEachIndexed { i, sh -> append("shader$i = $sh\n") }
        })
        return f.absolutePath
    }

    private fun shaderSource(name: String, vararg pragmas: String): String {
        val f = java.io.File(shaderFolder.root, name)
        f.parentFile?.mkdirs()
        f.writeText(pragmas.joinToString("\n"))
        return f.absolutePath
    }

    private fun pipelineHost(): FakeRaHost = host().apply {
        val a = shaderSource("a.slang", """#pragma parameter warp "Warp" 0.5 0.0 1.0 0.25""")
        val b = shaderSource("b.slang")
        appliedPreset = writePreset("current", a, b)
        presetFiles["crt/crt-geom"] = writePreset("crt-geom", shaderSource("geom.slang"))
    }

    private fun pipelineProvider(h: FakeRaHost) = RaIgmSettingsProvider(host = h, curated = false)

    private val SHADERS = CuratedCatalog.CATEGORY_SHADER

    private fun rowKeys(p: RaIgmSettingsProvider) =
        p.screen(listOf(SHADERS)).items.map { it.key }

    @Test
    fun `a write re-renders even when it landed on the value asked for`() {
        val h = host()
        val p = provider(h)
        var renders = 0
        p.setOnChanged { renders++ }
        p.screen(listOf(LATENCY))

        p.cycle("run_ahead_frames", 1)

        assertTrue("the write must trigger a render", renders > 0)
    }

    @Test
    fun `a write refreshes the neighbours its change handler moved`() {
        val h = host()
        h.settings["run_ahead_hide_warnings"] =
            h.settings["run_ahead_hide_warnings"]!!.copy(machineValue = MachineValue("false"))
        h.changeHandler = { key ->
            if (key == "run_ahead_frames") {
                h.settings["run_ahead_hide_warnings"] =
                    h.settings["run_ahead_hide_warnings"]!!.copy(
                        machineValue = MachineValue("true"), displayValue = "true",
                    )
            }
        }
        val p = provider(h)
        p.screen(listOf(LATENCY))

        p.cycle("run_ahead_frames", 1)

        val row = p.screen(listOf(LATENCY)).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>()
            .first { it.key == "run_ahead_hide_warnings" }
        assertEquals("the neighbour must be re-read, not left stale", "On", row.value)
    }

    @Test
    fun `All Settings gets the chain, not the picker`() {
        val keys = rowKeys(pipelineProvider(pipelineHost()))

        assertTrue(keys.containsAll(listOf("load", "save", "append", "prepend")))
        assertTrue(keys.contains("pass_0") && keys.contains("pass_1"))
        assertTrue(keys.contains("params"))
        assertFalse(keys.contains("video_shader_enable"))
        assertFalse(keys.contains("shader_passes"))
        assertFalse(keys.contains("shader_apply"))
    }

    @Test
    fun `the chain starts as the preset in force`() {
        val h = pipelineHost()
        pipelineProvider(h).screen(listOf(SHADERS))

        assertEquals(2, h.chain!!.passes.size)
    }

    @Test
    fun `a pass row names the shader it holds`() {
        val p = pipelineProvider(pipelineHost())
        assertEquals("Pass 0: a", p.screen(listOf(SHADERS)).items.first { it.key == "pass_0" }.label)
    }

    @Test
    fun `editing the chain touches only the model`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        p.screen(listOf(SHADERS))

        p.removeRow(listOf(SHADERS), rowKeys(p).indexOf("pass_1"))
        assertEquals(1, h.chain!!.passes.size)
        assertTrue(h.chainApplies.isEmpty())

        p.applyPendingChanges()
        assertEquals(listOf<String?>(null), h.chainApplies)
    }

    @Test
    fun `removing and reordering rearrange the passes`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        val first = rowKeys(p).indexOf("pass_0")

        assertEquals(first + 1, p.reorder(listOf(SHADERS), first, 1))
        assertEquals(listOf("b", "a"), h.chain!!.passes.map { it.shader.substringAfterLast('/').removeSuffix(".slang") })

        assertEquals(first, p.reorder(listOf(SHADERS), first, -1))
    }

    @Test
    fun `only a pass row can be picked up or taken out`() {
        val p = pipelineProvider(pipelineHost())
        val keys = rowKeys(p)

        assertTrue(p.canReorder(listOf(SHADERS), keys.indexOf("pass_0")))
        assertTrue(p.canRemoveRow(listOf(SHADERS), keys.indexOf("pass_0")))
        assertFalse(p.canReorder(listOf(SHADERS), keys.indexOf("load")))
        assertFalse(p.canReorder(listOf(SHADERS, "params"), 0))
    }

    @Test
    fun `loading replaces the chain and adding combines with it`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        p.screen(listOf(SHADERS))

        p.screen(listOf(SHADERS, "load", "crt"))
        p.activate("shader_preset:crt-geom")
        assertEquals(listOf("geom"), h.chain!!.passes.map { it.shader.substringAfterLast('/').removeSuffix(".slang") })

        p.screen(listOf(SHADERS, "append", "crt"))
        p.activate("shader_preset:crt-geom")
        assertEquals(2, h.chain!!.passes.size)

        p.screen(listOf(SHADERS, "prepend", "crt"))
        p.activate("shader_preset:crt-geom")
        assertEquals(3, h.chain!!.passes.size)
    }

    @Test
    fun `adding to the chain is offered only once there is one`() {
        val h = pipelineHost()
        h.appliedPreset = null
        val keys = rowKeys(pipelineProvider(h))

        assertTrue(keys.contains("load"))
        assertFalse(keys.contains("append"))
        assertFalse(keys.contains("prepend"))
    }

    @Test
    fun `parameters come from the shaders and step by their author's range`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        val row = p.screen(listOf(SHADERS, "params")).items.single()

        assertEquals("Warp", row.label)
        assertEquals("0.5", (row as GenericIgmSettingsItem.Choice).value)

        p.cycle("shader_param:warp", 1)
        assertEquals("0.75", h.chain!!.parameters["warp"])

        p.cycle("shader_param:warp", 1)
        p.cycle("shader_param:warp", 1)
        assertEquals("1", h.chain!!.parameters["warp"])
    }

    @Test
    fun `a pass filter cycles through unspecified as a real state`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        p.screen(listOf(SHADERS, "pass_0"))

        p.cycle("shader_filter:0", 1)
        assertEquals("true", h.chain!!.passes[0].settings["filter_linear"])
        p.cycle("shader_filter:0", 1)
        assertEquals("false", h.chain!!.passes[0].settings["filter_linear"])
        p.cycle("shader_filter:0", 1)
        assertNull(h.chain!!.passes[0].settings["filter_linear"])
    }

    @Test
    fun `a pass scale writes its type alongside its size`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        p.screen(listOf(SHADERS, "pass_0"))

        p.cycle("shader_scale:0", 1)
        val set = h.chain!!.passes[0].settings
        assertEquals("1.000000", set["scale_x"])
        assertEquals("source", set["scale_type_x"])

        p.cycle("shader_scale:0", -1)
        assertNull(h.chain!!.passes[0].settings["scale_x"])
        assertNull(h.chain!!.passes[0].settings["scale_type_x"])
    }

    @Test
    fun `every chain change offers to be kept`() {
        for (edit in listOf<(RaIgmSettingsProvider) -> Unit>(
            { it.cycle("shader_param:warp", 1) },
            { it.reorder(listOf(SHADERS), rowKeys(it).indexOf("pass_0"), 1) },
            { it.removeRow(listOf(SHADERS), rowKeys(it).indexOf("pass_1")) },
            { it.screen(listOf(SHADERS, "append", "crt")); it.activate("shader_preset:crt-geom") },
            { it.screen(listOf(SHADERS, "load", "crt")); it.activate("shader_preset:crt-geom") },
        )) {
            val p = pipelineProvider(pipelineHost())
            p.screen(listOf(SHADERS))
            assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
            edit(p)
            assertTrue(p.exitPrompt() is IgmSettingsExit.Prompt)
        }
    }

    @Test
    fun `saving names the preset it writes`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        p.screen(listOf(SHADERS))
        p.saveShaderPresetAs("crt soft")

        assertEquals(listOf("crt soft"), h.chainApplies)
        assertTrue(p.exitPrompt() is IgmSettingsExit.Prompt)
    }

    @Test
    fun `picking in the chain tree returns to the chain root, and the picker stays put`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)

        assertEquals(
            listOf(SHADERS),
            p.returnPathAfter("shader_preset:crt-geom", listOf(SHADERS, "load", "crt")),
        )
        assertNull(p.returnPathAfter("save", listOf(SHADERS)))
        assertNull(
            RaIgmSettingsProvider(host = h, curated = true)
                .returnPathAfter("shader_preset:crt-geom", listOf(SHADERS, "crt")),
        )
    }

    @Test
    fun `only the picker marks the applied preset`() {
        val h = pipelineHost()
        h.appliedPreset = "/Shaders/crt/crt-geom.slangp"

        val chainRow = pipelineProvider(h).screen(listOf(SHADERS, "load", "crt")).items
            .first { it.key == "shader_preset:crt-geom" }
        assertTrue(chainRow is GenericIgmSettingsItem.Action)

        val pickerRow = RaIgmSettingsProvider(host = h, curated = true)
            .screen(listOf(SHADERS, "crt")).items.first { it.key == "shader_preset:crt-geom" }
        assertTrue(pickerRow is GenericIgmSettingsItem.Choice)
    }

    @Test
    fun `the shader can be switched back on from the menu after a shortcut turned it off`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        val was = h.appliedPreset
        h.toggleShader()

        p.screen(listOf(SHADERS))
        val off = p.screen(listOf(SHADERS)).items.first { it.key == "enabled" }
        assertEquals(RaOptionStrings().off, (off as GenericIgmSettingsItem.Choice).value)

        p.cycle("enabled", 1)
        assertEquals("the preset it turned off is the one it brings back", was, h.appliedPreset)
        val on = p.screen(listOf(SHADERS)).items.first { it.key == "enabled" }
        assertEquals(RaOptionStrings().on, (on as GenericIgmSettingsItem.Choice).value)
    }

    @Test
    fun `turning the shader back on brings its passes back to the screen`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        h.toggleShader()
        p.screen(listOf(SHADERS))
        assertTrue(
            "an off shader has no passes to show",
            p.screen(listOf(SHADERS)).items.none { it.key.startsWith("pass_") },
        )

        p.cycle("enabled", 1)
        assertTrue(
            "the chain is reseeded from the restored preset",
            p.screen(listOf(SHADERS)).items.any { it.key.startsWith("pass_") },
        )
    }

    @Test
    fun `switched off, the toggle is the only row on the screen`() {
        val h = pipelineHost()
        val p = pipelineProvider(h)
        h.toggleShader()
        assertEquals(listOf("enabled"), p.screen(listOf(SHADERS)).items.map { it.key })
    }

    @Test
    fun `no toggle row is offered when there is no shader at all`() {
        val h = host()
        val p = pipelineProvider(h)
        assertTrue(p.screen(listOf(SHADERS)).items.none { it.key == "enabled" })
    }

    @Test
    fun `the picker still applies on selection`() {
        val h = pipelineHost()
        val p = RaIgmSettingsProvider(host = h, curated = true)
        p.screen(listOf(SHADERS, "crt"))
        p.activate("shader_preset:crt-geom")

        assertEquals(listOf(listOf("crt") to "crt-geom"), h.applyCalls)
    }

}
