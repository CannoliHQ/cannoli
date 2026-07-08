package dev.cannoli.scorza.server

import android.content.res.AssetManager
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.LaunchTarget
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
        launchTarget = LaunchTarget.RetroArch,
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
        assertEquals(1024L, game.getLong("size"))
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
        assertEquals("/files/art/SNES/zelda.png", game.getString("artUrl"))
    }

    @Test
    fun `missing rom file emits zeroed size and modified`() {
        val rom = fakeRom(9, "SNES/phantom.sfc", "Phantom")
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, File(tmp.root, "Roms"), "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertEquals(0L, game.getLong("size"))
        assertEquals(0L, game.getLong("modified"))
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
            launchTarget = dev.cannoli.scorza.model.LaunchTarget.RetroArch,
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
            launchTarget = LaunchTarget.RetroArch,
            raGameId = null,
        )
        val romLoose = Rom(
            id = 2L,
            path = looseGame,
            platformTag = "PS",
            displayName = "Loose Game",
            tags = null,
            artFile = null,
            launchTarget = LaunchTarget.RetroArch,
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
}
