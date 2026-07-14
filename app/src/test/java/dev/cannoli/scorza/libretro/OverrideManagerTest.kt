package dev.cannoli.scorza.libretro

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.core.IniParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OverrideManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var rootStr: String
    private lateinit var paths: CannoliPaths

    @Before fun setUp() {
        rootStr = tempFolder.root.absolutePath
        paths = CannoliPaths(rootStr)
    }

    private fun manager(platformTag: String = "PS", gameBaseName: String = "Game") =
        OverrideManager(rootStr, platformTag, gameBaseName)

    private fun writePlatformIni(platformTag: String, content: String) {
        val file = paths.systemOverrideFile(platformTag)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent() + "\n")
    }

    private fun writeGameIni(platformTag: String, gameBaseName: String, content: String) {
        val file = paths.gameOverrideFile(platformTag, gameBaseName)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent() + "\n")
    }

    // ---- load: defaults and cascade ----

    @Test fun `load returns defaults when no overrides exist`() {
        val s = manager().load()
        assertEquals(ScalingMode.CORE_REPORTED, s.scalingMode)
        assertEquals(ScreenEffect.NONE, s.screenEffect)
        assertEquals(Sharpness.SHARP, s.sharpness)
        assertEquals(4, s.maxFfSpeed)
        assertEquals("", s.shaderPreset)
        assertTrue(s.coreOptions.isEmpty())
        assertTrue(s.shaderParams.isEmpty())
        assertEquals(OverrideSource.GLOBAL, s.shortcutSource)
    }

    @Test fun `platform overrides apply when game has none`() {
        writePlatformIni(
            "PS",
            """
            [frontend]
            scaling=FULLSCREEN
            sharpness=SOFT
            max_ff_speed=8
            crt_curvature=2.0
            shader_preset=foo.glslp
            """
        )
        val s = manager().load()
        assertEquals(ScalingMode.FULLSCREEN, s.scalingMode)
        assertEquals(Sharpness.SOFT, s.sharpness)
        assertEquals(8, s.maxFfSpeed)
        assertEquals(2f, s.crtCurvature, 0f)
        assertEquals("foo.glslp", s.shaderPreset)
    }

    @Test fun `game overrides take precedence over platform`() {
        writePlatformIni(
            "PS",
            """
            [frontend]
            scaling=FULLSCREEN
            max_ff_speed=8
            """
        )
        writeGameIni(
            "PS", "Game",
            """
            [frontend]
            scaling=INTEGER
            """
        )
        val s = manager().load()
        assertEquals(ScalingMode.INTEGER, s.scalingMode)
        // Untouched fields fall back to platform
        assertEquals(8, s.maxFfSpeed)
    }

    @Test fun `core options merge across platform and game`() {
        writePlatformIni(
            "PS",
            """
            [options]
            swanstation_GPU_Renderer=Software
            swanstation_GPU_ResolutionScale=1
            """
        )
        writeGameIni(
            "PS", "Game",
            """
            [options]
            swanstation_GPU_ResolutionScale=4
            swanstation_GPU_TrueColor=true
            """
        )
        val s = manager().load()
        assertEquals("Software", s.coreOptions["swanstation_GPU_Renderer"])
        assertEquals("4", s.coreOptions["swanstation_GPU_ResolutionScale"])
        assertEquals("true", s.coreOptions["swanstation_GPU_TrueColor"])
    }

    @Test fun `shader_params are parsed as floats and merged`() {
        writePlatformIni(
            "PS",
            """
            [shader_params]
            scanlines=0.5
            curvature=1.7
            """
        )
        writeGameIni(
            "PS", "Game",
            """
            [shader_params]
            curvature=2.5
            ignored_garbage=not-a-float
            """
        )
        val s = manager().load()
        assertEquals(0.5f, s.shaderParams["scanlines"]!!, 0f)
        assertEquals(2.5f, s.shaderParams["curvature"]!!, 0f)
        assertNull(s.shaderParams["ignored_garbage"])
    }

    @Test fun `unknown enum values are ignored leaving defaults intact`() {
        writePlatformIni(
            "PS",
            """
            [frontend]
            scaling=NOT_A_REAL_MODE
            sharpness=SHARP
            """
        )
        val s = manager().load()
        assertEquals(ScalingMode.CORE_REPORTED, s.scalingMode)
        assertEquals(Sharpness.SHARP, s.sharpness)
    }

    // ---- shortcut source cascade ----

    @Test fun `shortcut source defaults to GLOBAL when nothing is set`() {
        val s = manager().load()
        assertEquals(OverrideSource.GLOBAL, s.shortcutSource)
    }

    @Test fun `shortcut source from platform applies when game has none`() {
        writePlatformIni(
            "PS",
            """
            [meta]
            shortcut_source=PLATFORM
            """
        )
        val s = manager().load()
        assertEquals(OverrideSource.PLATFORM, s.shortcutSource)
    }

    @Test fun `shortcut source from game beats platform`() {
        writePlatformIni(
            "PS",
            """
            [meta]
            shortcut_source=PLATFORM
            """
        )
        writeGameIni(
            "PS", "Game",
            """
            [meta]
            shortcut_source=GAME
            """
        )
        val s = manager().load()
        assertEquals(OverrideSource.GAME, s.shortcutSource)
    }

    // ---- savePlatform ----

    @Test fun `savePlatform persists frontend options and shader params`() {
        val mgr = manager()
        val s = OverrideManager.Settings(
            scalingMode = ScalingMode.INTEGER,
            screenEffect = ScreenEffect.SHADER,
            shaderPreset = "crt-cannoli.glslp",
            coreOptions = mapOf("swanstation_GPU_Renderer" to "Hardware"),
            shaderParams = mapOf("strength" to 0.7f)
        )
        mgr.savePlatform(s)

        val parsed = IniParser.parse(paths.systemOverrideFile("PS"))
        assertEquals("INTEGER", parsed.get("frontend", "scaling"))
        assertEquals("SHADER", parsed.get("frontend", "effect"))
        assertEquals("crt-cannoli.glslp", parsed.get("frontend", "shader_preset"))
        assertEquals("Hardware", parsed.get("options", "swanstation_GPU_Renderer"))
        assertEquals("0.7", parsed.get("shader_params", "strength"))
    }

    @Test fun `savePlatform removes options section when empty`() {
        // Pre-populate with options so we can verify removal.
        writePlatformIni(
            "PS",
            """
            [frontend]
            scaling=FULLSCREEN
            [options]
            stale_key=stale_value
            """
        )
        val mgr = manager()
        val s = OverrideManager.Settings(
            scalingMode = ScalingMode.FULLSCREEN,
            coreOptions = emptyMap()
        )
        mgr.savePlatform(s)

        val parsed = IniParser.parse(paths.systemOverrideFile("PS"))
        assertTrue(parsed.getSection("options").isEmpty())
    }

    // ---- saveGameDelta ----

    @Test fun `saveGameDelta writes only fields that diverge from baseline`() {
        val mgr = manager()
        val baseline = OverrideManager.Settings(
            scalingMode = ScalingMode.FULLSCREEN,
            sharpness = Sharpness.SHARP,
            shaderPreset = "preset.glslp",
            coreOptions = mapOf("a" to "1", "b" to "2"),
            shaderParams = mapOf("strength" to 0.5f)
        )
        val current = baseline.copy(
            scalingMode = ScalingMode.INTEGER, // diverges
            // sharpness same as baseline
            shaderPreset = "preset.glslp", // same
            coreOptions = mapOf("a" to "1", "b" to "999"), // b diverges
            shaderParams = mapOf("strength" to 0.5f) // same
        )
        mgr.saveGameDelta(current, baseline)

        val parsed = IniParser.parse(paths.gameOverrideFile("PS", "Game"))
        // Only the changed scaling field should be in frontend
        val frontend = parsed.getSection("frontend")
        assertEquals("INTEGER", frontend["scaling"])
        assertNull(frontend["sharpness"])
        assertNull(frontend["shader_preset"])
        // Only the changed core option
        val options = parsed.getSection("options")
        assertEquals("999", options["b"])
        assertNull(options["a"])
        // Shader params identical, so no shader_params section
        assertTrue(parsed.getSection("shader_params").isEmpty())
    }

    @Test fun `show_fps round-trips through platform and game files`() {
        val mgr = manager()
        mgr.savePlatform(OverrideManager.Settings(showFps = true))
        assertTrue(mgr.load().showFps)

        val baseline = mgr.loadPlatformBaseline()
        mgr.saveGameDelta(baseline.copy(showFps = false), baseline)
        val gameFrontend = IniParser.parse(paths.gameOverrideFile("PS", "Game")).getSection("frontend")
        assertEquals("false", gameFrontend["show_fps"])
        assertFalse(mgr.load().showFps)
    }

    @Test fun `saveGameDelta deletes the file when no fields diverge`() {
        // Pre-create the game file
        writeGameIni(
            "PS", "Game",
            """
            [frontend]
            scaling=INTEGER
            """
        )
        val mgr = manager()
        val baseline = OverrideManager.Settings()
        mgr.saveGameDelta(baseline.copy(), baseline)
        assertFalse(paths.gameOverrideFile("PS", "Game").exists())
    }

    // ---- shortcut helpers ----

    @Test fun `saveShortcuts writes to the file matching the source`() {
        val mgr = manager()
        val shortcuts = mapOf(
            ShortcutAction.SAVE_STATE to setOf(96, 102),
            ShortcutAction.LOAD_STATE to setOf(96, 99)
        )
        mgr.saveShortcuts(OverrideSource.PLATFORM, shortcuts)

        val parsed = IniParser.parse(paths.systemOverrideFile("PS")).getSection("shortcuts")
        assertEquals(2, parsed.size)
        // Order within set isn't guaranteed in serialization; check by parsing back.
        val saveStateChord = parsed["SAVE_STATE"]!!.split(",").mapNotNull { it.toIntOrNull() }.toSet()
        val loadStateChord = parsed["LOAD_STATE"]!!.split(",").mapNotNull { it.toIntOrNull() }.toSet()
        assertEquals(setOf(96, 102), saveStateChord)
        assertEquals(setOf(96, 99), loadStateChord)
    }

    @Test fun `loadShortcutsForSource round-trips chords through saveShortcuts`() {
        val mgr = manager()
        val original = mapOf(
            ShortcutAction.RESET_GAME to setOf(96, 100),
            ShortcutAction.OPEN_MENU to setOf(96)
        )
        mgr.saveShortcuts(OverrideSource.GAME, original)

        val loaded = mgr.loadShortcutsForSource(OverrideSource.GAME)
        assertEquals(original, loaded)
    }

    @Test fun `saveShortcutSource writes meta entry to the game file`() {
        manager().saveShortcutSource(OverrideSource.PLATFORM)
        val meta = IniParser.parse(paths.gameOverrideFile("PS", "Game")).getSection("meta")
        assertEquals("PLATFORM", meta["shortcut_source"])
    }

    // ---- legacy core-named override migration ----

    @Test fun `init copies legacy Cores override into the platform file when missing`() {
        val legacy = File(paths.configOverrides, "Cores/swanstation.ini")
        legacy.parentFile?.mkdirs()
        legacy.writeText(
            """
            [frontend]
            scaling=INTEGER
            """.trimIndent() + "\n"
        )

        OverrideManager(rootStr, "PS", "Game", coreName = "swanstation")

        val platformFile = paths.systemOverrideFile("PS")
        assertTrue(platformFile.exists())
        assertEquals("INTEGER", IniParser.parse(platformFile).get("frontend", "scaling"))
    }

    // ---- frontendEquals: inputRemap ----

    @Test fun `frontendEquals returns false when inputRemap differs`() {
        val a = OverrideManager.Settings(
            inputRemap = mapOf(dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 256),
        )
        val b = OverrideManager.Settings(
            inputRemap = emptyMap(),
        )
        assertFalse(a.frontendEquals(b))
        assertFalse(b.frontendEquals(a))
    }

    @Test fun `frontendEquals returns true when inputRemap matches`() {
        val a = OverrideManager.Settings(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 256,
                dev.cannoli.scorza.input.CanonicalButton.BTN_EAST to 1,
            ),
        )
        val b = a.copy()
        assertTrue(a.frontendEquals(b))
    }

    // ---- load: inputRemap ----

    @Test fun `load reads inputRemap from platform ini`() {
        writePlatformIni(
            "PS",
            """
            [input_remap]
            BTN_SOUTH=256
            BTN_EAST=1
            """
        )
        val s = manager().load()
        assertEquals(256, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH])
        assertEquals(1, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_EAST])
    }

    @Test fun `game inputRemap overrides platform inputRemap`() {
        writePlatformIni(
            "PS",
            """
            [input_remap]
            BTN_SOUTH=256
            BTN_EAST=1
            """
        )
        writeGameIni(
            "PS", "Game",
            """
            [input_remap]
            BTN_SOUTH=1
            """
        )
        val s = manager().load()
        assertEquals(1, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH])
        assertEquals(1, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_EAST])
    }

    @Test fun `load skips unknown CanonicalButton keys in inputRemap`() {
        writePlatformIni(
            "PS",
            """
            [input_remap]
            BTN_SOUTH=256
            BTN_GARBAGE=999
            """
        )
        val s = manager().load()
        assertEquals(1, s.inputRemap.size)
        assertEquals(256, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH])
    }

    @Test fun `load skips non-integer values in inputRemap`() {
        writePlatformIni(
            "PS",
            """
            [input_remap]
            BTN_SOUTH=256
            BTN_EAST=notanumber
            """
        )
        val s = manager().load()
        assertEquals(1, s.inputRemap.size)
        assertEquals(256, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH])
    }

    @Test fun `load preserves zero as a valid override (Unbound)`() {
        writePlatformIni(
            "PS",
            """
            [input_remap]
            BTN_SOUTH=0
            """
        )
        val s = manager().load()
        assertEquals(0, s.inputRemap[dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH])
    }

    // ---- savePlatform: inputRemap ----

    @Test fun `savePlatform writes inputRemap entries that differ from global default`() {
        val mgr = manager()
        val settings = mgr.load().apply {
            // BTN_SOUTH default is RETRO_B (1). Override to RETRO_A (256).
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 256,
            )
        }
        mgr.savePlatform(settings)
        val raw = paths.systemOverrideFile("PS").readText()
        assertTrue(raw.contains("BTN_SOUTH=256") || raw.contains("BTN_SOUTH = 256"))
    }

    @Test fun `savePlatform omits inputRemap entries that equal global default`() {
        val mgr = manager()
        val settings = mgr.load().apply {
            // BTN_SOUTH default is already RETRO_B (1) per CanonicalRetroMap.
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to
                    dev.cannoli.scorza.input.runtime.CanonicalRetroMap.maskOf(
                        dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH
                    ),
            )
        }
        mgr.savePlatform(settings)
        val raw = paths.systemOverrideFile("PS").readText()
        assertFalse(raw.contains("BTN_SOUTH"))
    }

    @Test fun `savePlatform omits empty inputRemap section entirely`() {
        val mgr = manager()
        val settings = mgr.load().apply { inputRemap = emptyMap() }
        mgr.savePlatform(settings)
        val raw = paths.systemOverrideFile("PS").readText()
        assertFalse(raw.contains("[input_remap]"))
    }

    @Test fun `savePlatform preserves Unbound (zero) override`() {
        val mgr = manager()
        val settings = mgr.load().apply {
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SELECT to 0,
            )
        }
        mgr.savePlatform(settings)
        val raw = paths.systemOverrideFile("PS").readText()
        // BTN_SELECT default is non-zero, so 0 is a real override and must be written.
        assertTrue(raw.contains("BTN_SELECT=0") || raw.contains("BTN_SELECT = 0"))
    }

    // ---- saveGameDelta: inputRemap ----

    @Test fun `saveGameDelta writes inputRemap entries that differ from platform`() {
        val mgr = manager()
        val baseline = OverrideManager.Settings(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 256,
            ),
        )
        val settings = baseline.copy(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 1,
                dev.cannoli.scorza.input.CanonicalButton.BTN_EAST to 1,
            ),
        )
        mgr.saveGameDelta(settings, baseline)
        val raw = paths.gameOverrideFile("PS", "Game").readText()
        assertTrue(raw.contains("BTN_SOUTH=1") || raw.contains("BTN_SOUTH = 1"))
        assertTrue(raw.contains("BTN_EAST=1") || raw.contains("BTN_EAST = 1"))
    }

    @Test fun `saveGameDelta omits inputRemap entry when game value matches global default and baseline absent`() {
        val mgr = manager()
        val baseline = OverrideManager.Settings(inputRemap = emptyMap())
        val settings = baseline.copy(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_EAST to
                    dev.cannoli.scorza.input.runtime.CanonicalRetroMap.maskOf(
                        dev.cannoli.scorza.input.CanonicalButton.BTN_EAST
                    ),
            ),
        )
        mgr.saveGameDelta(settings, baseline)
        val raw = if (paths.gameOverrideFile("PS", "Game").exists())
            paths.gameOverrideFile("PS", "Game").readText() else ""
        assertFalse(raw.contains("BTN_EAST"))
    }

    @Test fun `saveGameDelta omits inputRemap entries that match platform baseline`() {
        val mgr = manager()
        val baseline = OverrideManager.Settings(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 256,
            ),
        )
        val settings = baseline.copy(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH to 256,
            ),
        )
        mgr.saveGameDelta(settings, baseline)
        val raw = if (paths.gameOverrideFile("PS", "Game").exists())
            paths.gameOverrideFile("PS", "Game").readText() else ""
        assertFalse(raw.contains("BTN_SOUTH"))
    }

    @Test fun `saveGameDelta writes zero override when platform had non-zero`() {
        val mgr = manager()
        val baseline = OverrideManager.Settings(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SELECT to 4,
            ),
        )
        val settings = baseline.copy(
            inputRemap = mapOf(
                dev.cannoli.scorza.input.CanonicalButton.BTN_SELECT to 0,
            ),
        )
        mgr.saveGameDelta(settings, baseline)
        val raw = paths.gameOverrideFile("PS", "Game").readText()
        assertTrue(raw.contains("BTN_SELECT=0") || raw.contains("BTN_SELECT = 0"))
    }

    @Test fun `init does not overwrite an existing platform file even if a legacy file exists`() {
        val legacy = File(paths.configOverrides, "Cores/swanstation.ini")
        legacy.parentFile?.mkdirs()
        legacy.writeText("[frontend]\nscaling=INTEGER\n")

        writePlatformIni(
            "PS",
            """
            [frontend]
            scaling=FULLSCREEN
            """
        )

        OverrideManager(rootStr, "PS", "Game", coreName = "swanstation")

        val parsed = IniParser.parse(paths.systemOverrideFile("PS"))
        assertEquals("FULLSCREEN", parsed.get("frontend", "scaling"))
    }

    @Test fun `diagonals are allowed when nothing is set`() {
        assertTrue(manager().load().allowDiagonals)
    }

    @Test fun `allow_diagonals round-trips through platform and game files`() {
        val mgr = manager()
        mgr.savePlatform(OverrideManager.Settings(allowDiagonals = false))
        assertFalse(mgr.load().allowDiagonals)

        val baseline = mgr.load()
        mgr.saveGameDelta(baseline.copy(allowDiagonals = true), baseline)
        assertTrue(mgr.load().allowDiagonals)
    }

    @Test fun `the game allow_diagonals overrides the platform value`() {
        writePlatformIni("PS", "[frontend]\nallow_diagonals = true")
        writeGameIni("PS", "Game", "[frontend]\nallow_diagonals = false")
        assertFalse(manager().load().allowDiagonals)
    }

    @Test fun `saveGameDelta omits allow_diagonals when it matches the platform baseline`() {
        val mgr = manager()
        mgr.savePlatform(OverrideManager.Settings(allowDiagonals = false))
        val baseline = mgr.load()
        // Diverge on an unrelated field, so the game file is written but must not carry the
        // inherited allow_diagonals along with it.
        mgr.saveGameDelta(baseline.copy(showFps = true), baseline)
        val section = IniParser.parse(paths.gameOverrideFile("PS", "Game")).getSection("frontend")
        assertEquals("true", section["show_fps"])
        assertNull(section["allow_diagonals"])
        assertFalse(mgr.load().allowDiagonals)
    }
}
