package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GameListViewModelReloadPositionTest {

    private fun rom(id: Long, name: String) = ListItem.RomItem(
        Rom(id = id, path = File("/roms/$name.sfc"), platformTag = "snes", displayName = name)
    )

    @Test fun `rename re-sorts list and selection follows the renamed item without forcing scroll`() {
        // Before: [Alpha(1), Mango(2), Zelda(3)], user had Zelda selected at index 2.
        // Rename Zelda -> "Apple": new natural-sorted list is [Alpha(1), Apple(3), Mango(2)].
        // The old index-based logic kept index 2 (Mango) selected. We must follow the renamed
        // item (index 1) but NOT force a scroll target, or the list slams the item to the top.
        val newItems = listOf(rom(1, "Alpha"), rom(3, "Apple"), rom(2, "Mango"))
        val (idx, scroll) = reloadPosition(
            items = newItems,
            preserveId = "rom:3",
            preserveIndex = 2,
            preserveScroll = 2,
            prevCount = 3,
        )
        assertEquals(1, idx)
        assertEquals(-1, scroll)
    }

    @Test fun `missing id with changed count resets to top`() {
        val newItems = listOf(rom(1, "Alpha"), rom(2, "Mango"))
        val (idx, scroll) = reloadPosition(newItems, "rom:3", preserveIndex = 2, preserveScroll = 2, prevCount = 3)
        assertEquals(0, idx)
        assertEquals(0, scroll)
    }

    @Test fun `no prior id preserves index and scroll when count unchanged`() {
        val newItems = listOf(rom(1, "Alpha"), rom(2, "Mango"), rom(3, "Zelda"))
        val (idx, scroll) = reloadPosition(newItems, null, preserveIndex = 2, preserveScroll = 1, prevCount = 3)
        assertEquals(2, idx)
        assertEquals(1, scroll)
    }

    @Test fun `no prior id with unknown prev count preserves saved position (subfolder restore)`() {
        val newItems = listOf(rom(1, "Alpha"), rom(2, "Mango"), rom(3, "Zelda"))
        val (idx, scroll) = reloadPosition(newItems, null, preserveIndex = 1, preserveScroll = 1, prevCount = -1)
        assertEquals(1, idx)
        assertEquals(1, scroll)
    }

    @Test fun `empty list returns top`() {
        val (idx, scroll) = reloadPosition(emptyList(), "rom:1", preserveIndex = 5, preserveScroll = 5, prevCount = 3)
        assertEquals(0, idx)
        assertEquals(0, scroll)
    }

    @Test fun `stable id is keyed by type and entity id`() {
        assertEquals("rom:7", stableIdOf(rom(7, "Foo")))
        assertEquals("app:9", stableIdOf(ListItem.AppItem(App(id = 9, type = AppType.PORT, displayName = "Quake", packageName = "com.q"))))
        assertEquals("col:3", stableIdOf(ListItem.CollectionItem(Collection(id = 3, displayName = "JRPGs"))))
        assertEquals("col:4", stableIdOf(ListItem.ChildCollectionItem(Collection(id = 4, displayName = "SRPGs"))))
        assertEquals("sub:RPG", stableIdOf(ListItem.SubfolderItem(name = "RPG", path = "/roms/RPG")))
        assertNull(stableIdOf(null))
    }
}
