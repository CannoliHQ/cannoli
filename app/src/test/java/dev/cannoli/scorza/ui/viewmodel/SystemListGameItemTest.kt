package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SystemListGameItemTest {

    private fun gameItem(item: ListItem) = SystemListViewModel.ListItem.GameItem(item)

    @Test fun `rom art surfaces on the home screen`() {
        val art = File("/cannoli/Art/SNES/Chrono Trigger.png")
        val rom = Rom(id = 1, path = File("/cannoli/Roms/SNES/ct.sfc"), platformTag = "SNES", displayName = "Chrono Trigger", artFile = art)
        assertEquals(art, gameItem(ListItem.RomItem(rom)).artFile)
    }

    @Test fun `tool art surfaces on the home screen`() {
        val art = File("/cannoli/Art/TOOLS/Termux.png")
        val app = App(id = 1, type = AppType.TOOL, displayName = "Termux", packageName = "com.termux", artFile = art)
        assertEquals(art, gameItem(ListItem.AppItem(app)).artFile)
    }

    @Test fun `port art surfaces on the home screen`() {
        val art = File("/cannoli/Art/PORTS/Portal 2.png")
        val app = App(id = 2, type = AppType.PORT, displayName = "Portal 2", packageName = "com.valve.portal2", artFile = art)
        assertEquals(art, gameItem(ListItem.AppItem(app)).artFile)
    }

    @Test fun `an app with no art has no art path`() {
        val app = App(id = 3, type = AppType.TOOL, displayName = "Termux", packageName = "com.termux")
        assertNull(gameItem(ListItem.AppItem(app)).artFile)
    }
}
