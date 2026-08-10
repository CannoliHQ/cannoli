package dev.cannoli.scorza.input.screen

import dev.cannoli.igm.GuideFile
import dev.cannoli.igm.GuideType
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuideInputHandlerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var nav: NavigationController
    private lateinit var handler: GuideInputHandler

    private val files = listOf(
        GuideFile(File("/x/a.txt"), GuideType.TXT),
        GuideFile(File("/x/b.txt"), GuideType.TXT),
        GuideFile(File("/x/c.txt"), GuideType.TXT),
    )

    @Before fun setup() {
        nav = NavigationController()
        handler = GuideInputHandler(nav, mockk<SettingsRepository>(relaxed = true))
    }

    @Test fun startGuidesResolvesTheSameDirForAnNfdRomPathAsItsNfcBaseName() {
        // "e" (U+0065) followed by a combining acute accent (U+0301) is NFD; the guide dir on
        // disk is keyed by the NFC base name, same as the IGM's romBaseName.
        val decomposed = "Poke\u0301mon Red (USA)"
        val precomposed = "Pok\u00e9mon Red (USA)"
        val guideDir = File(tmp.root, "Guides/gb/$precomposed").apply { mkdirs() }
        File(guideDir, "manual.pdf").writeText("x")

        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns tmp.root.absolutePath
        val h = GuideInputHandler(nav, settings)

        h.startGuides(Rom(id = 1, path = File("/roms/gb/$decomposed.gb"), platformTag = "gb", displayName = decomposed))

        assertEquals(listOf("manual.pdf"), h.controller.guideFiles.value.map { it.name })
    }

    @Test fun pickerDownMovesSelection() {
        nav.push(LauncherScreen.GuidePicker(files, selectedIndex = 0))
        handler.onDown()
        assertEquals(1, (nav.currentScreen as LauncherScreen.GuidePicker).selectedIndex)
    }

    @Test fun pickerUpWrapsToLast() {
        nav.push(LauncherScreen.GuidePicker(files, selectedIndex = 0))
        handler.onUp()
        assertEquals(2, (nav.currentScreen as LauncherScreen.GuidePicker).selectedIndex)
    }

    @Test fun pickerBackPops() {
        nav.push(LauncherScreen.GuidePicker(files))
        handler.onBack()
        assertEquals(LauncherScreen.SystemList, nav.currentScreen)
    }
}
