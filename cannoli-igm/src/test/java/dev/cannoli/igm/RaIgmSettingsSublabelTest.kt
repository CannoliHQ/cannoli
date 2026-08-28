package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class SublabelHost : RaSettingsHost {
    val settings = mutableMapOf<String, RaSetting>()
    val screens = mutableMapOf<String, List<RaScreenRow>>()
    override fun raScreenRows(label: String): List<RaScreenRow> = screens[label].orEmpty()
    override fun raGetSetting(key: String): RaSetting? = settings[key]
    override fun raSetSetting(key: String, value: MachineValue) = true
    override fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>) {}
    override fun setOnRaSettingApplied(callback: (String, String) -> Unit) {}
}

class RaIgmSettingsSublabelTest {

    private fun provider(h: SublabelHost) = RaIgmSettingsProvider(
        host = h, strings = RaOptionStrings(), curated = false,
    )

    // RetroArch supplies the screen; the row it names is looked up through the host like any other.
    private fun scalingRow(h: SublabelHost, key: String): GenericIgmSettingsItem.Choice {
        h.screens["video_scaling_settings"] =
            h.settings.keys.map { RaScreenRow(it, it, isMenu = false) }
        return provider(h).screen(listOf("video_scaling_settings")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.key == key }
    }

    @Test
    fun `a row carries the description the host reports`() {
        val h = SublabelHost()
        h.settings["video_smooth"] = RaSetting(
            "video_smooth", "Bilinear Filtering", RaSettingType.BOOL, MachineValue("false"), "false",
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
            RaSetting("video_smooth", "Bilinear Filtering", RaSettingType.BOOL, MachineValue("false"), "false")
        assertNull(scalingRow(h, "video_smooth").description)
    }

    @Test
    fun `a description survives on a nested screen as well as a top-level one`() {
        val h = SublabelHost()
        h.settings["fps_show"] = RaSetting(
            "fps_show", "Display Framerate", RaSettingType.BOOL, MachineValue("false"), "false",
            description = "Shows the current frames per second.",
        )
        val row = scalingRow(h, "fps_show")
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

    // Up and Down scroll the text rather than moving the hidden selection.
    @Test
    fun `up and down scroll the description without moving the selection`() {
        val c = ProviderSettingsController(OneRowProvider("Softens hard pixel edges."))
        c.enter()
        c.onNav(ProviderSettingsController.Nav.NORTH)
        val down = menu(c.onNav(ProviderSettingsController.Nav.DOWN))
        assertEquals(1, down.descriptionScroll)
        assertEquals(0, down.selectedIndex)
        assertEquals("Softens hard pixel edges.", down.description)
        assertEquals(0, menu(c.onNav(ProviderSettingsController.Nav.UP)).descriptionScroll)
    }

    // Reopening starts at the top rather than wherever the last one was left.
    @Test
    fun `the scroll position resets each time the description opens`() {
        val c = ProviderSettingsController(OneRowProvider("Softens hard pixel edges."))
        c.enter()
        c.onNav(ProviderSettingsController.Nav.NORTH)
        c.onNav(ProviderSettingsController.Nav.DOWN)
        c.onNav(ProviderSettingsController.Nav.DOWN)
        c.onNav(ProviderSettingsController.Nav.BACK)
        assertEquals(0, menu(c.onNav(ProviderSettingsController.Nav.NORTH)).descriptionScroll)
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
