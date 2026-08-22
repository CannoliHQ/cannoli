package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class SublabelHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raSetSetting(key: String, value: String) = true
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {}
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}
    override fun getLocalToggle(key: String, default: Boolean) = default
    override fun setLocalToggle(key: String, value: Boolean) {}
}

class RaIgmSettingsSublabelTest {

    private fun provider(h: SublabelHost) = RaIgmSettingsProvider(
        host = h, strings = RaOptionStrings(), debugBuild = false, curated = false, onOpenNativeMenu = {},
    )

    private fun scalingRow(h: SublabelHost, key: String) =
        provider(h).screen(listOf("video", "scaling")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.key == key }

    @Test
    fun `a row carries the description the host reports`() {
        val h = SublabelHost()
        h.settings["video_smooth"] = RaSetting(
            "video_smooth", "Bilinear Filtering", RaSettingType.BOOL, "false",
            description = "Add a slight blur to the image to soften hard pixel edges.",
        )
        assertEquals(
            "Add a slight blur to the image to soften hard pixel edges.",
            scalingRow(h, "video_smooth").description,
        )
    }

    // RetroArch has no sublabel for every setting, and a core option has none at all.
    @Test
    fun `a row without one carries null rather than an empty string`() {
        val h = SublabelHost()
        h.settings["video_smooth"] =
            RaSetting("video_smooth", "Bilinear Filtering", RaSettingType.BOOL, "false")
        assertNull(scalingRow(h, "video_smooth").description)
    }

    // A local toggle is built by hand rather than read from RetroArch, so it has no sublabel to
    // carry and must not inherit one from whatever RaSetting was constructed last.
    @Test
    fun `a local toggle has no description`() {
        val h = SublabelHost()
        val row = provider(h).screen(listOf("osd")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().firstOrNull { it.key == "cannoli_osd_reset" }
        assertNull(row?.description)
    }

    @Test
    fun `a description survives into a subcategory screen as well as a category one`() {
        val h = SublabelHost()
        h.settings["fps_show"] = RaSetting(
            "fps_show", "Display Framerate", RaSettingType.BOOL, "false",
            description = "Shows the current frames per second.",
        )
        val row = provider(h).screen(listOf("osd")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.key == "fps_show" }
        assertEquals("Shows the current frames per second.", row.description)
    }
}

class DescriptionToggleTest {

    private class OneRowProvider(private val description: String?) : IgmSettingsProvider {
        override fun screen(path: List<String>) = GenericIgmSettingsScreen(
            "Video",
            listOf(
                GenericIgmSettingsItem.Choice("a", "Bilinear Filtering", "Off", description = description),
                GenericIgmSettingsItem.Choice("b", "Vertical Sync", "On"),
            ),
        )
        override fun cycle(itemKey: String, direction: Int) {}
        override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
        override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
        override fun setOnChanged(callback: () -> Unit) {}
    }

    private fun menu(s: ProviderSettingsController.State) = s as ProviderSettingsController.State.Menu

    @Test
    fun `north shows the description and back returns to the list`() {
        val c = ProviderSettingsController(OneRowProvider("Softens hard pixel edges."))
        c.enter()
        assertNull(menu(c.state()).description)
        assertEquals("Softens hard pixel edges.", menu(c.onNav(ProviderSettingsController.Nav.NORTH)).description)
        assertNull(menu(c.onNav(ProviderSettingsController.Nav.BACK)).description)
    }

    // Back has to close the description before it closes the screen, or one press would do both.
    @Test
    fun `back out of a description does not leave the settings screen`() {
        val c = ProviderSettingsController(OneRowProvider("Softens hard pixel edges."))
        c.enter()
        c.onNav(ProviderSettingsController.Nav.NORTH)
        val after = c.onNav(ProviderSettingsController.Nav.BACK)
        assertEquals(ProviderSettingsController.State.Menu::class, after::class)
    }

    @Test
    fun `north does nothing on a row with no description`() {
        val c = ProviderSettingsController(OneRowProvider(null))
        c.enter()
        assertNull(menu(c.onNav(ProviderSettingsController.Nav.NORTH)).description)
    }

    // The description covers the list, so the list stops taking input. Moving a selection the user
    // cannot see would also close the description as a side effect of the redraw.
    @Test
    fun `the list is frozen while the description is up`() {
        val c = ProviderSettingsController(OneRowProvider("Softens hard pixel edges."))
        c.enter()
        c.onNav(ProviderSettingsController.Nav.NORTH)
        for (nav in listOf(
            ProviderSettingsController.Nav.UP,
            ProviderSettingsController.Nav.DOWN,
            ProviderSettingsController.Nav.LEFT,
            ProviderSettingsController.Nav.RIGHT,
            ProviderSettingsController.Nav.CONFIRM,
            ProviderSettingsController.Nav.NORTH,
        )) {
            val s = menu(c.onNav(nav))
            assertEquals("$nav must not move the selection", 0, s.selectedIndex)
            assertEquals("$nav must not close the description", "Softens hard pixel edges.", s.description)
        }
        assertNull(menu(c.onNav(ProviderSettingsController.Nav.BACK)).description)
    }

    @Test
    fun `a frozen list does not cycle the value underneath`() {
        var cycles = 0
        val provider = object : IgmSettingsProvider {
            override fun screen(path: List<String>) = GenericIgmSettingsScreen(
                "Video",
                listOf(GenericIgmSettingsItem.Choice("a", "Bilinear Filtering", "Off", description = "Softens edges.")),
            )
            override fun cycle(itemKey: String, direction: Int) { cycles++ }
            override fun activate(itemKey: String): IgmSettingsExit.Prompt? = null
            override fun exitPrompt(): IgmSettingsExit = IgmSettingsExit.Close
            override fun setOnChanged(callback: () -> Unit) {}
        }
        val c = ProviderSettingsController(provider)
        c.enter()
        c.onNav(ProviderSettingsController.Nav.NORTH)
        c.onNav(ProviderSettingsController.Nav.RIGHT)
        c.onNav(ProviderSettingsController.Nav.LEFT)
        assertEquals(0, cycles)
    }
}
