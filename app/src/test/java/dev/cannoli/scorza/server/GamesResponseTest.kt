package dev.cannoli.scorza.server

import android.content.res.AssetManager
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.RomDirectoryWalker
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class GamesResponseTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fakeRom(id: Long, romPath: String, displayName: String, art: File? = null): Rom = Rom(
        id = id,
        path = File(tmp.root, "Roms/$romPath"),
        platformTag = "SNES",
        displayName = displayName,
        tags = null,
        artFile = art,
        raGameId = null,
    )

    private fun mockRepo(items: List<Rom>): RomsRepository {
        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("SNES") } returns items
        return repo
    }

    @Test
    fun `empty platform returns empty games array`() {
        val repo = mockRepo(emptyList())
        val json = GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo")
        val parsed = JSONObject(json)
        assertEquals("SNES", parsed.getString("platform"))
        assertEquals("Super Nintendo", parsed.getString("displayName"))
        assertEquals(0, parsed.getJSONArray("games").length())
    }

    @Test
    fun `single rom with no resources reports zero counts`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        val romFile = File(romDir, "chrono.sfc").also { it.writeBytes(ByteArray(1024)) }
        val rom = fakeRom(42, "SNES/chrono.sfc", "Chrono Trigger")
        val repo = mockRepo(listOf(rom))

        val json = GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo")
        val game = JSONObject(json).getJSONArray("games").getJSONObject(0)

        assertEquals(42, game.getLong("id"))
        assertEquals("chrono.sfc", game.getString("rom"))
        assertEquals("Chrono Trigger", game.getString("displayName"))
        assertFalse(game.getBoolean("hasArt"))
        assertEquals(0, game.getInt("savesCount"))
        assertEquals(0, game.getInt("statesCount"))
        assertEquals(0, game.getInt("guidesCount"))
        assertFalse(game.has("artUrl"))
    }

    @Test
    fun `counts saves states and guides next to the rom`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        val romFile = File(romDir, "earthbound.sfc").also { it.writeBytes(ByteArray(2048)) }
        File(tmp.root, "Saves/SNES").mkdirs()
        File(tmp.root, "Saves/SNES/earthbound.srm").writeBytes(ByteArray(8))
        File(tmp.root, "Save States/SNES/earthbound").mkdirs()
        File(tmp.root, "Save States/SNES/earthbound/earthbound.state").writeBytes(ByteArray(16))
        File(tmp.root, "Save States/SNES/earthbound/earthbound.state1").writeBytes(ByteArray(16))
        File(tmp.root, "Guides/SNES/earthbound").mkdirs()
        File(tmp.root, "Guides/SNES/earthbound/walkthrough.txt").writeBytes(ByteArray(4))

        val rom = fakeRom(7, "SNES/earthbound.sfc", "EarthBound")
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)

        assertEquals(1, game.getInt("savesCount"))
        assertEquals(2, game.getInt("statesCount"))
        assertEquals(1, game.getInt("guidesCount"))
    }

    @Test
    fun `counts cheat files next to the rom`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "zelda.sfc").also { it.writeBytes(ByteArray(64)) }
        File(tmp.root, "Cheats/SNES/zelda").also { it.mkdirs() }
        File(tmp.root, "Cheats/SNES/zelda/zelda.cht").writeText("cheats = 1\ncheat0_desc = \"X\"\n")
        File(tmp.root, "Cheats/SNES/zelda/notes.txt").writeText("ignored")
        val rom = fakeRom(7, "SNES/zelda.sfc", "Zelda")
        val repo = mockRepo(listOf(rom))

        val json = GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo")
        val game = JSONObject(json).getJSONArray("games").getJSONObject(0)

        assertEquals(1, game.getInt("cheatsCount"))
    }

    @Test
    fun `hasArt emits artUrl when rom has art`() {
        val artFile = File(tmp.root, "Art/SNES/zelda.png").apply { parentFile.mkdirs(); writeBytes(ByteArray(4)) }
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "zelda.sfc").writeBytes(ByteArray(128))
        val rom = fakeRom(3, "SNES/zelda.sfc", "Zelda", art = artFile)
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertTrue(game.getBoolean("hasArt"))
        val artUrl = game.getString("artUrl")
        assertEquals("/api/art/SNES/zelda.png", artUrl.substringBefore('?'))
        assertTrue(artUrl, artUrl.substringAfter('?').startsWith("v="))
    }

    @Test
    fun `artUrl percent-encodes characters that would break the path`() {
        File(tmp.root, "Art/SNES/Sonic & Knuckles (USA).png")
            .apply { parentFile.mkdirs(); writeBytes(ByteArray(4)) }
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "Sonic & Knuckles (USA).sfc").writeBytes(ByteArray(64))
        val rom = fakeRom(7, "SNES/Sonic & Knuckles (USA).sfc", "Sonic & Knuckles")
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertEquals(
            "/api/art/SNES/Sonic%20%26%20Knuckles%20%28USA%29.png",
            game.getString("artUrl").substringBefore('?'),
        )
    }

    @Test
    fun `artUrl carries a version token that changes when the art directory changes`() {
        val artDir = File(tmp.root, "Art/SNES").apply { mkdirs() }
        File(artDir, "zelda.png").writeBytes(ByteArray(4))
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "zelda.sfc").writeBytes(ByteArray(64))
        val repo = mockRepo(listOf(fakeRom(3, "SNES/zelda.sfc", "Zelda")))

        fun currentArtUrl(): String =
            JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
                .getJSONArray("games").getJSONObject(0).getString("artUrl")

        val before = currentArtUrl()
        artDir.setLastModified(artDir.lastModified() + 10_000)
        val after = currentArtUrl()

        assertEquals(before.substringBefore('?'), after.substringBefore('?'))
        assertNotEquals(before.substringAfter('?'), after.substringAfter('?'))
    }

    @Test
    fun `game detail emits zeroed size and modified for a missing rom file`() {
        val rom = fakeRom(9, "SNES/phantom.sfc", "Phantom")
        val repo = mockk<RomsRepository>()
        every { repo.gameById(9L) } returns rom

        val game = JSONObject(
            GamesResponse.buildOne(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo", 9L)!!
        )
        assertEquals(0L, game.getLong("size"))
        assertEquals(0L, game.getLong("modified"))
    }

    @Test
    fun `game detail reports the rom file size`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "chrono.sfc").writeBytes(ByteArray(1024))
        val rom = fakeRom(42, "SNES/chrono.sfc", "Chrono Trigger")
        val repo = mockk<RomsRepository>()
        every { repo.gameById(42L) } returns rom

        val game = JSONObject(
            GamesResponse.buildOne(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo", 42L)!!
        )
        assertEquals(1024L, game.getLong("size"))
    }

    @Test
    fun `the games list omits size and modified`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "chrono.sfc").writeBytes(ByteArray(1024))
        val repo = mockRepo(listOf(fakeRom(42, "SNES/chrono.sfc", "Chrono Trigger")))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertFalse(game.has("size"))
        assertFalse(game.has("modified"))
    }

    @Test
    fun `counts auto slot and highest numbered slot`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "metroid.sfc").writeBytes(ByteArray(2048))
        File(tmp.root, "Save States/SNES/metroid").mkdirs()
        File(tmp.root, "Save States/SNES/metroid/metroid.state.auto").writeBytes(ByteArray(16))
        File(tmp.root, "Save States/SNES/metroid/metroid.state").writeBytes(ByteArray(16))
        File(tmp.root, "Save States/SNES/metroid/metroid.state9").writeBytes(ByteArray(16))

        val rom = fakeRom(5, "SNES/metroid.sfc", "Metroid")
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertEquals(3, game.getInt("statesCount"))
    }

    @Test
    fun `gameToJson reports multiDisc from the rom path extension`() {
        val romDir = File(tmp.root, "Roms/PS").also { it.mkdirs() }
        File(romDir, "Metal Gear Solid").mkdirs()
        File(romDir, "Metal Gear Solid/Metal Gear Solid.m3u").writeText("Metal Gear Solid (Disc 1).bin\n")
        val rom = Rom(
            id = 20,
            path = File(romDir, "Metal Gear Solid/Metal Gear Solid.m3u"),
            platformTag = "PS",
            displayName = "Metal Gear Solid",
            tags = null,
            artFile = null,
            raGameId = null,
        )
        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("PS") } returns listOf(rom)
        val json = GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "PS", "PS")
        assertTrue(json.contains("\"multiDisc\":true"))
        assertFalse(json.contains("\"discPaths\""))
    }

    @Test
    fun `single game lookup returns object with platform context`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "fzero.sfc").writeBytes(ByteArray(64))
        val rom = fakeRom(11, "SNES/fzero.sfc", "F-Zero")
        val repo = mockk<RomsRepository>()
        every { repo.gameById(11L) } returns rom

        val json = GamesResponse.buildOne(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo", 11L)
        val parsed = JSONObject(json!!)
        assertEquals("SNES", parsed.getString("platform"))
        assertEquals("Super Nintendo", parsed.getString("platformDisplayName"))
        assertEquals(11L, parsed.getLong("id"))
    }

    @Test
    fun `buildList emits folder per game and top-level folders array`() {
        val psRomsDir = File(tmp.root, "Roms/PS").also { it.mkdirs() }
        val rpgsDir = File(psRomsDir, "RPGs").also { it.mkdirs() }
        val subGame = File(rpgsDir, "Some Game.iso").also { it.writeBytes(ByteArray(4)) }
        val looseGame = File(psRomsDir, "Loose Game.iso").also { it.writeBytes(ByteArray(4)) }

        val romInFolder = Rom(
            id = 1L,
            path = subGame,
            platformTag = "PS",
            displayName = "Some Game",
            tags = null,
            artFile = null,
            raGameId = null,
        )
        val romLoose = Rom(
            id = 2L,
            path = looseGame,
            platformTag = "PS",
            displayName = "Loose Game",
            tags = null,
            artFile = null,
            raGameId = null,
        )

        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("PS") } returns listOf(romInFolder, romLoose)

        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws FileNotFoundException()
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns tmp.root
        every { paths.romDir } returns File(tmp.root, "Roms")
        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
        val walker = RomDirectoryWalker(paths, assets, arcade)

        val json = GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "PS", "PlayStation", walker)
        val parsed = JSONObject(json)

        val games = parsed.getJSONArray("games")
        assertEquals(2, games.length())

        val game0 = games.getJSONObject(0)
        assertEquals(1L, game0.getLong("id"))
        assertEquals("RPGs", game0.getString("folder"))

        val game1 = games.getJSONObject(1)
        assertEquals(2L, game1.getLong("id"))
        assertEquals("", game1.getString("folder"))

        val folders = parsed.getJSONArray("folders")
        assertEquals(1, folders.length())
        assertEquals("RPGs", folders.getString(0))
    }

    private fun listingsForLibraryOf(gameCount: Int): Int {
        val root = tmp.newFolder("lib$gameCount")
        val romDir = File(root, "Roms/SNES").also { it.mkdirs() }
        listOf("Art", "Saves", "Save States", "Guides", "Cheats")
            .forEach { File(root, "$it/SNES").mkdirs() }
        val roms = (0 until gameCount).map { i ->
            File(romDir, "Game $i.sfc").writeBytes(ByteArray(16))
            File(root, "Art/SNES/Game $i.png").writeBytes(ByteArray(4))
            Rom(
                id = i.toLong(),
                path = File(romDir, "Game $i.sfc"),
                platformTag = "SNES",
                displayName = "Game $i",
                tags = null,
                artFile = null,
                raGameId = null,
            )
        }
        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("SNES") } returns roms

        var listings = 0
        GamesResponse.buildList(
            repo, root, File(root, "Roms"), "SNES", "Super Nintendo",
            listDir = { listings++; it.listFiles() },
        )
        return listings
    }

    @Test
    fun `buildList stops early once the client has gone away`() {
        val root = tmp.newFolder("abandoned")
        val romDir = File(root, "Roms/SNES").also { it.mkdirs() }
        val roms = (0 until 500).map { i ->
            File(romDir, "Game $i.sfc").writeBytes(ByteArray(16))
            Rom(
                id = i.toLong(),
                path = File(romDir, "Game $i.sfc"),
                platformTag = "SNES",
                displayName = "Game $i",
                tags = null,
                artFile = null,
                raGameId = null,
            )
        }
        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("SNES") } returns roms

        assertThrows(RequestAbandonedException::class.java) {
            GamesResponse.buildList(
                repo, root, File(root, "Roms"), "SNES", "Super Nintendo",
                isCancelled = { true },
            )
        }
    }

    @Test
    fun `buildList completes when the client is still connected`() {
        val root = tmp.newFolder("connected")
        val romDir = File(root, "Roms/SNES").also { it.mkdirs() }
        val roms = (0 until 500).map { i ->
            File(romDir, "Game $i.sfc").writeBytes(ByteArray(16))
            Rom(
                id = i.toLong(),
                path = File(romDir, "Game $i.sfc"),
                platformTag = "SNES",
                displayName = "Game $i",
                tags = null,
                artFile = null,
                raGameId = null,
            )
        }
        val repo = mockk<RomsRepository>()
        every { repo.allRomsForPlatform("SNES") } returns roms

        val json = GamesResponse.buildList(
            repo, root, File(root, "Roms"), "SNES", "Super Nintendo",
            isCancelled = { false },
        )
        assertEquals(500, JSONObject(json).getJSONArray("games").length())
    }

    @Test
    fun `resource directories are listed once each regardless of library size`() {
        assertEquals(5, listingsForLibraryOf(50))
        assertEquals(5, listingsForLibraryOf(200))
    }
}
