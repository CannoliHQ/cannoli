package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameListViewModelSearchTest {

    private fun rom(id: Long, name: String) = Rom(
        id = id,
        path = File("/roms/$name.sfc"),
        platformTag = "snes",
        displayName = name,
    )

    private val zelda = ListItem.RomItem(rom(1, "The Legend of Zelda"))
    private val mario = ListItem.RomItem(rom(2, "Super Mario World"))
    private val items = listOf(zelda, mario)

    @Test fun `null term returns all items`() {
        assertEquals(items, applyItemFilter(items, null))
    }

    @Test fun `blank term returns all items`() {
        assertEquals(items, applyItemFilter(items, ""))
    }

    @Test fun `term matches case-insensitively`() {
        val result = applyItemFilter(items, "zel")
        assertEquals(listOf(zelda), result)
    }

    @Test fun `no-match term yields empty list`() {
        val result = applyItemFilter(items, "metroid")
        assertTrue(result.isEmpty())
    }

    @Test fun `search matches across diacritics`() {
        val pokemon = ListItem.RomItem(rom(1, "Pokémon"))
        assertEquals(listOf(pokemon), applyItemFilter(listOf(pokemon), "poke"))
    }

    @Test fun `app item matched by displayName`() {
        val app = ListItem.AppItem(App(id = 1, type = AppType.PORT, displayName = "Quake", packageName = "com.quake"))
        val appItems = listOf(app)
        assertEquals(appItems, applyItemFilter(appItems, "qua"))
        assertTrue(applyItemFilter(appItems, "zelda").isEmpty())
    }

    @Test fun `subfolder item matched by name`() {
        val folder = ListItem.SubfolderItem(name = "RPG", path = "/roms/RPG")
        val folderItems = listOf(folder)
        assertEquals(folderItems, applyItemFilter(folderItems, "rpg"))
        assertTrue(applyItemFilter(folderItems, "action").isEmpty())
    }

    @Test fun `collection item matched by displayName`() {
        val col = ListItem.CollectionItem(Collection(id = 1, displayName = "Favorites"))
        val colItems = listOf(col)
        assertEquals(colItems, applyItemFilter(colItems, "fav"))
        assertTrue(applyItemFilter(colItems, "recent").isEmpty())
    }

    @Test fun `child collection item matched by displayName`() {
        val child = ListItem.ChildCollectionItem(Collection(id = 2, displayName = "JRPGs"))
        val childItems = listOf(child)
        assertEquals(childItems, applyItemFilter(childItems, "jrpg"))
        assertTrue(applyItemFilter(childItems, "action").isEmpty())
    }

    @Test fun `origin tag is platform tag for rom`() {
        val item = ListItem.RomItem(rom(1, "Zelda"))
        assertEquals("SNES", globalOriginTag(item, "Tools", "Ports", "Collection"))
    }

    @Test fun `origin tag is tools or ports label for app`() {
        val tool = ListItem.AppItem(App(id = 1, type = AppType.TOOL, displayName = "Files", packageName = "com.files"))
        val port = ListItem.AppItem(App(id = 2, type = AppType.PORT, displayName = "Quake", packageName = "com.quake"))
        assertEquals("Tools", globalOriginTag(tool, "Tools", "Ports", "Collection"))
        assertEquals("Ports", globalOriginTag(port, "Tools", "Ports", "Collection"))
    }

    @Test fun `origin tag is collection label for collection`() {
        val col = ListItem.CollectionItem(Collection(id = 1, displayName = "JRPGs"))
        assertEquals("Collection", globalOriginTag(col, "Tools", "Ports", "Collection"))
    }

    @Test fun `origin tag is null for subfolder`() {
        val folder = ListItem.SubfolderItem(name = "RPG", path = "/roms/RPG")
        assertNull(globalOriginTag(folder, "Tools", "Ports", "Collection"))
    }
}
