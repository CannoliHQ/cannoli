package dev.cannoli.scorza.server

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        discFiles = null,
        raGameId = null,
    )

    private fun mockRepo(items: List<Rom>): RomsRepository {
        val repo = mockk<RomsRepository>()
        every { repo.gamesForPlatform("SNES", null) } returns items.map { ListItem.RomItem(it) }
        return repo
    }

    @Test
    fun `empty platform returns empty games array`() {
        val repo = mockRepo(emptyList())
        val json = GamesResponse.buildList(repo, tmp.root, "SNES", "Super Nintendo")
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

        val json = GamesResponse.buildList(repo, tmp.root, "SNES", "Super Nintendo")
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

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)

        assertEquals(1, game.getInt("savesCount"))
        assertEquals(2, game.getInt("statesCount"))
        assertEquals(1, game.getInt("guidesCount"))
    }

    @Test
    fun `hasArt emits artUrl when rom has art`() {
        val artFile = File(tmp.root, "Art/SNES/zelda.png").apply { parentFile.mkdirs(); writeBytes(ByteArray(4)) }
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "zelda.sfc").writeBytes(ByteArray(128))
        val rom = fakeRom(3, "SNES/zelda.sfc", "Zelda", art = artFile)
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertTrue(game.getBoolean("hasArt"))
        assertEquals("/files/art/SNES/zelda.png", game.getString("artUrl"))
    }

    @Test
    fun `missing rom file emits zeroed size and modified`() {
        val rom = fakeRom(9, "SNES/phantom.sfc", "Phantom")
        val repo = mockRepo(listOf(rom))

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, "SNES", "Super Nintendo"))
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

        val game = JSONObject(GamesResponse.buildList(repo, tmp.root, "SNES", "Super Nintendo"))
            .getJSONArray("games").getJSONObject(0)
        assertEquals(3, game.getInt("statesCount"))
    }

    @Test
    fun `single game lookup returns object with platform context`() {
        val romDir = File(tmp.root, "Roms/SNES").also { it.mkdirs() }
        File(romDir, "fzero.sfc").writeBytes(ByteArray(64))
        val rom = fakeRom(11, "SNES/fzero.sfc", "F-Zero")
        val repo = mockk<RomsRepository>()
        every { repo.gameById(11L) } returns rom

        val json = GamesResponse.buildOne(repo, tmp.root, "SNES", "Super Nintendo", 11L)
        val parsed = JSONObject(json!!)
        assertEquals("SNES", parsed.getString("platform"))
        assertEquals("Super Nintendo", parsed.getString("platformDisplayName"))
        assertEquals(11L, parsed.getLong("id"))
    }
}
