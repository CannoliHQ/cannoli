package dev.cannoli.scorza.libretro.settings

import dev.cannoli.igm.GenericIgmSettingsItem
import dev.cannoli.igm.IgmSettingsExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIgmSettingsProviderTest {

    private fun provider(host: FakeLauncherSettingsHost = FakeLauncherSettingsHost()) =
        LauncherIgmSettingsProvider(host, LauncherSettingsStrings()) to host

    @Test
    fun `root lists the five categories in declaration order`() {
        val (p, _) = provider()
        val items = p.screen(emptyList()).items
        assertEquals(
            listOf("Video", "Emulator", "Input", "Advanced", "Info"),
            items.map { it.label },
        )
        assertTrue(items.dropLast(1).all { it is GenericIgmSettingsItem.Category })
        assertTrue(items.last() is GenericIgmSettingsItem.Action)
    }

    @Test
    fun `video omits shader settings when there are no params`() {
        val (p, _) = provider()
        val items = p.screen(listOf("video")).items
        assertEquals(
            listOf("Screen Scaling", "Screen Sharpness", "Shader", "Overlay"),
            items.map { it.label },
        )
    }

    @Test
    fun `video includes shader settings when params exist`() {
        val host = FakeLauncherSettingsHost().apply { hasShaderParams = true }
        val (p, _) = provider(host)
        assertEquals(
            listOf("Screen Scaling", "Screen Sharpness", "Shader", "Shader Settings", "Overlay"),
            p.screen(listOf("video")).items.map { it.label },
        )
    }

    @Test
    fun `shader settings is the only action and overlay stays a choice`() {
        val host = FakeLauncherSettingsHost().apply { hasShaderParams = true }
        val (p, _) = provider(host)
        val items = p.screen(listOf("video")).items
        assertTrue(items.single { it.label == "Shader Settings" } is GenericIgmSettingsItem.Action)
        assertTrue(items.single { it.label == "Overlay" } is GenericIgmSettingsItem.Choice)
        assertTrue(items.single { it.label == "Shader" } is GenericIgmSettingsItem.Choice)
    }

    @Test
    fun `row values come from the host labels`() {
        val host = FakeLauncherSettingsHost().apply { shader = "crt-cannoli" }
        val (p, _) = provider(host)
        val row = p.screen(listOf("video")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>()
            .first { it.label == "Shader" }
        assertEquals("crt-cannoli", row.value)
    }

    @Test
    fun `cycling a video row delegates to the host`() {
        val (p, host) = provider()
        p.cycle("video.scaling", 1)
        p.cycle("video.sharpness", -1)
        assertEquals(listOf("scaling:1", "sharpness:-1"), host.calls)
    }

    @Test
    fun `exit prompt closes when nothing is dirty`() {
        val (p, _) = provider()
        assertTrue(p.exitPrompt() is IgmSettingsExit.Close)
    }

    @Test
    fun `exit prompt offers save scopes when dirty and routes each choice`() {
        val host = FakeLauncherSettingsHost().apply { dirty = true; platformName = "SNES" }
        val (p, h) = provider(host)
        val prompt = p.exitPrompt() as IgmSettingsExit.Prompt
        assertEquals(listOf("Save for SNES", "Save for this game", "Discard"), prompt.options)
        prompt.onChoice(0)
        prompt.onChoice(1)
        prompt.onChoice(2)
        assertEquals(listOf("savePlatform", "saveGame"), h.calls)
    }

    @Test
    fun `info and shader settings activate the host`() {
        val (p, h) = provider()
        p.activate("info")
        p.activate("video.shaderSettings")
        assertEquals(listOf("openInfo", "openShaderSettings"), h.calls)
    }

    @Test
    fun `advanced hides controller rows when only one controller type exists`() {
        val (p, _) = provider()
        assertEquals(
            listOf("Max FF Speed", "Show FPS", "Debug HUD"),
            p.screen(listOf("advanced")).items.map { it.label },
        )
    }

    @Test
    fun `advanced shows a single controller row for one occupied port`() {
        val host = FakeLauncherSettingsHost().apply {
            controllerTypes = listOf(fakeControllerType(0), fakeControllerType(1))
            occupiedPorts = listOf(0)
        }
        val (p, _) = provider(host)
        assertEquals(
            listOf("Controller Type", "Max FF Speed", "Show FPS", "Debug HUD"),
            p.screen(listOf("advanced")).items.map { it.label },
        )
    }

    @Test
    fun `advanced shows per-port rows for multiple occupied ports`() {
        val host = FakeLauncherSettingsHost().apply {
            controllerTypes = listOf(fakeControllerType(0), fakeControllerType(1))
            occupiedPorts = listOf(0, 2)
        }
        val (p, _) = provider(host)
        assertEquals(
            listOf("P1 Controller", "P3 Controller", "Max FF Speed", "Show FPS", "Debug HUD"),
            p.screen(listOf("advanced")).items.map { it.label },
        )
    }

    @Test
    fun `max ff speed renders with an x suffix`() {
        val host = FakeLauncherSettingsHost().apply { maxFfSpeed = 8 }
        val (p, _) = provider(host)
        val row = p.screen(listOf("advanced")).items
            .filterIsInstance<GenericIgmSettingsItem.Choice>()
            .first { it.label == "Max FF Speed" }
        assertEquals("8x", row.value)
    }

    @Test
    fun `input hides dpad mode unless experimental features are on`() {
        val (p, _) = provider()
        assertEquals(
            listOf("Button Mappings", "Shortcuts", "Left Stick as D-Pad"),
            p.screen(listOf("input")).items.map { it.label },
        )
    }

    @Test
    fun `input shows dpad mode when experimental features are on`() {
        val host = FakeLauncherSettingsHost().apply { experimentalFeatures = true }
        val (p, _) = provider(host)
        val items = p.screen(listOf("input")).items
        assertEquals(
            listOf("Button Mappings", "Shortcuts", "Left Stick as D-Pad", "D-Pad Mode"),
            items.map { it.label },
        )
        assertEquals(
            "8-Way",
            items.filterIsInstance<GenericIgmSettingsItem.Choice>().first { it.label == "D-Pad Mode" }.value,
        )
    }

    @Test
    fun `button mappings and shortcuts are actions`() {
        val (p, host) = provider()
        p.activate("input.buttons")
        p.activate("input.shortcuts")
        assertEquals(listOf("openButtons", "openShortcuts"), host.calls)
    }
}
