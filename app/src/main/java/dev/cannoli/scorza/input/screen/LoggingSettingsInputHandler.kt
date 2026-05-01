package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.LoggingPrefs
import javax.inject.Inject

@ActivityScoped
class LoggingSettingsInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.LoggingSettings? =
        nav.currentScreen as? LauncherScreen.LoggingSettings

    private fun categories() = LoggingPrefs.Category.entries

    override fun onUp() {
        val screen = current() ?: return
        val count = categories().size
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).mod(count)))
    }

    override fun onDown() {
        val screen = current() ?: return
        val count = categories().size
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).mod(count)))
    }

    override fun onLeft() = toggleSelected()
    override fun onRight() = toggleSelected()

    override fun onBack() {
        nav.pop()
    }

    private fun toggleSelected() {
        val screen = current() ?: return
        val category = categories().getOrNull(screen.selectedIndex) ?: return
        val newValue = !LoggingPrefs.isEnabled(category)
        LoggingPrefs.set(category, newValue)
        when (category) {
            LoggingPrefs.Category.FILE_SCANNER -> settings.loggingFileScanner = newValue
            LoggingPrefs.Category.ROM_SCAN -> settings.loggingRomScan = newValue
            LoggingPrefs.Category.INPUT -> settings.loggingInput = newValue
            LoggingPrefs.Category.SESSION -> settings.loggingSession = newValue
        }
        nav.replaceTop(screen.copy())
    }
}
