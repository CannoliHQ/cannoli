package dev.cannoli.scorza.input.screen

import dev.cannoli.igm.GuideFile
import dev.cannoli.igm.GuideType
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class GuideInputHandlerTest {

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
