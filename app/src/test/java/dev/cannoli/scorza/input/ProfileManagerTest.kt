package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.util.IniParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var rootStr: String
    private lateinit var paths: CannoliPaths
    private lateinit var profiles: ProfileManager

    @Before fun setUp() {
        rootStr = tempFolder.root.absolutePath
        paths = CannoliPaths(rootStr)
        profiles = ProfileManager(rootStr)
    }

    private fun writeProfile(name: String, body: String) {
        val file = paths.profileFile(name)
        file.parentFile?.mkdirs()
        file.writeText(body.trimIndent() + "\n")
    }

    private fun writeGameMeta(platformTag: String, gameBaseName: String, body: String) {
        val file = paths.gameOverrideFile(platformTag, gameBaseName)
        file.parentFile?.mkdirs()
        file.writeText(body.trimIndent() + "\n")
    }

    private fun writePlatformMeta(platformTag: String, body: String) {
        val file = paths.systemOverrideFile(platformTag)
        file.parentFile?.mkdirs()
        file.writeText(body.trimIndent() + "\n")
    }

    // ---- ensureDefaults / listProfiles ----

    @Test fun `ensureDefaults creates the two protected profiles`() {
        profiles.ensureDefaults()
        assertTrue(profiles.profileExists(ProfileManager.NAVIGATION))
        assertTrue(profiles.profileExists(ProfileManager.DEFAULT_GAME))
    }

    @Test fun `ensureDefaults removes any legacy 'Default' profile file`() {
        writeProfile("Default", "[controls]\nbtn_south=96\n")
        profiles.ensureDefaults()
        assertFalse(profiles.profileExists("Default"))
    }

    @Test fun `listProfiles puts protected profiles first then customs alphabetically`() {
        writeProfile("Zelda", "")
        writeProfile("Mario", "")
        profiles.ensureDefaults()
        val list = profiles.listProfiles()
        assertEquals(
            listOf(ProfileManager.NAVIGATION, ProfileManager.DEFAULT_GAME, "Mario", "Zelda"),
            list
        )
    }

    @Test fun `listGameProfiles excludes Navigation profile`() {
        profiles.ensureDefaults()
        writeProfile("Custom", "")
        val list = profiles.listGameProfiles()
        assertFalse(list.contains(ProfileManager.NAVIGATION))
        assertTrue(list.contains(ProfileManager.DEFAULT_GAME))
        assertTrue(list.contains("Custom"))
    }

    @Test fun `listProfiles returns just the defaults when profiles dir is missing`() {
        // Don't call ensureDefaults; profiles dir does not exist yet.
        assertEquals(
            listOf(ProfileManager.NAVIGATION, ProfileManager.DEFAULT_GAME),
            profiles.listProfiles()
        )
    }

    // ---- readControls / saveControls ----

    @Test fun `readControls returns empty map for unknown profile`() {
        assertTrue(profiles.readControls("nonexistent").isEmpty())
    }

    @Test fun `readControls parses controls section as integers`() {
        writeProfile(
            "Custom",
            """
            [controls]
            btn_south=96
            btn_east=97
            invalid=not-a-number
            """
        )
        val controls = profiles.readControls("Custom")
        assertEquals(96, controls["btn_south"])
        assertEquals(97, controls["btn_east"])
        assertNull(controls["invalid"])
    }

    @Test fun `readControls migrates legacy a b x y face button keys`() {
        writeProfile(
            "Legacy",
            """
            [controls]
            btn_a=96
            btn_b=97
            btn_x=99
            btn_y=100
            """
        )
        val controls = profiles.readControls("Legacy")
        assertEquals(96, controls["btn_south"])
        assertEquals(97, controls["btn_east"])
        assertEquals(99, controls["btn_west"])
        assertEquals(100, controls["btn_north"])
        assertNull(controls["btn_a"])
        assertNull(controls["btn_b"])
        assertNull(controls["btn_x"])
        assertNull(controls["btn_y"])

        // Migration writes back to disk.
        val parsed = IniParser.parse(paths.profileFile("Legacy")).getSection("controls")
        assertEquals("96", parsed["btn_south"])
        assertNull(parsed["btn_a"])
    }

    @Test fun `saveControls round-trips through readControls`() {
        val source = mapOf("btn_south" to 96, "btn_east" to 97, "btn_l" to 102)
        profiles.saveControls("Round", source)
        val loaded = profiles.readControls("Round")
        assertEquals(source, loaded)
    }

    @Test fun `saveControls preserves other sections in the file`() {
        writeProfile(
            "Mixed",
            """
            [meta]
            note=keepme
            [controls]
            btn_south=1
            """
        )
        profiles.saveControls("Mixed", mapOf("btn_south" to 96))
        val parsed = IniParser.parse(paths.profileFile("Mixed"))
        assertEquals("keepme", parsed.get("meta", "note"))
        assertEquals("96", parsed.get("controls", "btn_south"))
    }

    // ---- createProfile / deleteProfile ----

    @Test fun `createProfile creates an empty profile`() {
        assertTrue(profiles.createProfile("New"))
        assertTrue(profiles.profileExists("New"))
    }

    @Test fun `createProfile copies provided controls`() {
        val seed = mapOf("btn_south" to 96)
        assertTrue(profiles.createProfile("Seeded", copyFrom = seed))
        assertEquals(seed, profiles.readControls("Seeded"))
    }

    @Test fun `createProfile rejects blank or protected names`() {
        assertFalse(profiles.createProfile(""))
        assertFalse(profiles.createProfile("   "))
        assertFalse(profiles.createProfile(ProfileManager.NAVIGATION))
        assertFalse(profiles.createProfile(ProfileManager.DEFAULT_GAME))
        // Case-insensitive on the protected check.
        assertFalse(profiles.createProfile(ProfileManager.NAVIGATION.lowercase()))
    }

    @Test fun `createProfile refuses to overwrite an existing profile`() {
        assertTrue(profiles.createProfile("Once"))
        assertFalse(profiles.createProfile("Once"))
    }

    @Test fun `deleteProfile refuses to delete protected profiles`() {
        profiles.ensureDefaults()
        assertFalse(profiles.deleteProfile(ProfileManager.NAVIGATION))
        assertFalse(profiles.deleteProfile(ProfileManager.DEFAULT_GAME))
        assertTrue(profiles.profileExists(ProfileManager.NAVIGATION))
    }

    @Test fun `deleteProfile removes a custom profile`() {
        profiles.createProfile("Doomed")
        assertTrue(profiles.deleteProfile("Doomed"))
        assertFalse(profiles.profileExists("Doomed"))
    }

    // ---- resolveProfile cascade ----

    @Test fun `resolveProfile defaults to DEFAULT_GAME when nothing is set`() {
        assertEquals(ProfileManager.DEFAULT_GAME, profiles.resolveProfile("PS", "Game"))
    }

    @Test fun `resolveProfile uses platform meta when no game meta`() {
        profiles.createProfile("PlatformChoice")
        writePlatformMeta(
            "PS",
            """
            [meta]
            profile=PlatformChoice
            """
        )
        assertEquals("PlatformChoice", profiles.resolveProfile("PS", "Game"))
    }

    @Test fun `resolveProfile prefers game meta over platform meta`() {
        profiles.createProfile("PlatformChoice")
        profiles.createProfile("GameChoice")
        writePlatformMeta(
            "PS",
            """
            [meta]
            profile=PlatformChoice
            """
        )
        writeGameMeta(
            "PS", "Game",
            """
            [meta]
            profile=GameChoice
            """
        )
        assertEquals("GameChoice", profiles.resolveProfile("PS", "Game"))
    }

    @Test fun `resolveProfile falls back to DEFAULT_GAME when referenced profile is missing`() {
        writeGameMeta(
            "PS", "Game",
            """
            [meta]
            profile=Ghost
            """
        )
        assertEquals(ProfileManager.DEFAULT_GAME, profiles.resolveProfile("PS", "Game"))
    }

    // ---- saveProfileSelection ----

    @Test fun `saveProfileSelection writes the profile name into game meta`() {
        profiles.createProfile("Custom")
        profiles.saveProfileSelection("PS", "Game", "Custom")
        val meta = IniParser.parse(paths.gameOverrideFile("PS", "Game")).getSection("meta")
        assertEquals("Custom", meta["profile"])
    }

    @Test fun `saveProfileSelection with DEFAULT_GAME removes meta profile entry`() {
        // Pre-existing override with a custom profile selection.
        profiles.createProfile("Custom")
        profiles.saveProfileSelection("PS", "Game", "Custom")
        // Switching back to default should clear it.
        profiles.saveProfileSelection("PS", "Game", ProfileManager.DEFAULT_GAME)
        // File may be deleted if no other sections remain.
        val file = paths.gameOverrideFile("PS", "Game")
        if (file.exists()) {
            val meta = IniParser.parse(file).getSection("meta")
            assertNull(meta["profile"])
        }
    }

    @Test fun `saveProfileSelection with DEFAULT_GAME preserves unrelated sections`() {
        // Pre-existing override with frontend section + profile in meta.
        writeGameMeta(
            "PS", "Game",
            """
            [meta]
            profile=Custom
            [frontend]
            scaling=INTEGER
            """
        )
        profiles.saveProfileSelection("PS", "Game", ProfileManager.DEFAULT_GAME)
        val parsed = IniParser.parse(paths.gameOverrideFile("PS", "Game"))
        assertNull(parsed.get("meta", "profile"))
        assertEquals("INTEGER", parsed.get("frontend", "scaling"))
    }

    // ---- isProtected ----

    @Test fun `isProtected checks the static set`() {
        assertTrue(ProfileManager.isProtected(ProfileManager.NAVIGATION))
        assertTrue(ProfileManager.isProtected(ProfileManager.DEFAULT_GAME))
        assertFalse(ProfileManager.isProtected("Custom"))
    }
}
