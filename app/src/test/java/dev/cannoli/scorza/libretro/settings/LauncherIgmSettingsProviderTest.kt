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
}
