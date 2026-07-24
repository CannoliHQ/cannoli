package dev.cannoli.scorza.libretro.settings

import dev.cannoli.igm.GenericIgmSettingsItem
import org.junit.Assert.assertEquals
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
        assertEquals(4, items.count { it is GenericIgmSettingsItem.Category })
        assertEquals(1, items.count { it is GenericIgmSettingsItem.Action })
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
    fun `shader settings is an action, the other video rows are choices`() {
        val host = FakeLauncherSettingsHost().apply { hasShaderParams = true }
        val (p, _) = provider(host)
        val items = p.screen(listOf("video")).items
        assertEquals(1, items.count { it is GenericIgmSettingsItem.Action })
        assertEquals(4, items.count { it is GenericIgmSettingsItem.Choice })
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
}
