package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import javax.inject.Inject

@ActivityScoped
class SetupInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val activityActions: ActivityActions,
) : ScreenInputHandler {

    var onStartInstalling: ((targetPath: String) -> Unit)? = null

    override fun onUp() {
        val screen = nav.currentScreen as? LauncherScreen.Setup ?: return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).coerceAtLeast(0)))
    }

    override fun onDown() {
        val screen = nav.currentScreen as? LauncherScreen.Setup ?: return
        val maxIndex = if (screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom") 1 else 0
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).coerceAtMost(maxIndex)))
    }

    override fun onLeft() {
        val screen = nav.currentScreen as? LauncherScreen.Setup ?: return
        if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
            nav.replaceTop(screen.copy(
                volumeIndex = (screen.volumeIndex - 1 + screen.volumes.size) % screen.volumes.size,
                customPath = null
            ))
        }
    }

    override fun onRight() {
        val screen = nav.currentScreen as? LauncherScreen.Setup ?: return
        if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
            nav.replaceTop(screen.copy(
                volumeIndex = (screen.volumeIndex + 1) % screen.volumes.size,
                customPath = null
            ))
        }
    }

    override fun onConfirm() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.Setup -> {
                val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
                if (screen.selectedIndex == 1 && isCustom) {
                    val entries = setupCoordinator.listDirectories("/storage/")
                    nav.push(LauncherScreen.DirectoryBrowser(
                        purpose = BrowsePurpose.SETUP,
                        currentPath = "/storage/",
                        entries = entries
                    ))
                }
            }
            is LauncherScreen.Installing -> {
                if (screen.finished) {
                    settings.sdCardRoot = screen.targetPath
                    settings.setupCompleted = true
                    activityActions.restartApp()
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                if (hasSelect && screen.selectedIndex == 0) {
                    val resolved = if (setupCoordinator.isVolumeRoot(screen.currentPath))
                        screen.currentPath + "Cannoli/"
                    else screen.currentPath
                    val setupIdx = nav.screenStack.indexOfLast { it is LauncherScreen.Setup }
                    if (setupIdx >= 0) {
                        val setup = nav.screenStack[setupIdx] as LauncherScreen.Setup
                        val path = if (resolved.endsWith("/")) resolved else "$resolved/"
                        nav.screenStack[setupIdx] = setup.copy(customPath = path)
                    }
                    nav.pop()
                } else {
                    val entryIdx = screen.selectedIndex - if (hasSelect) 1 else 0
                    val folderName = screen.entries.getOrNull(entryIdx) ?: return
                    val newPath = setupCoordinator.resolveDirectoryEntry(screen.currentPath, folderName)
                    val newEntries = setupCoordinator.listDirectories(newPath)
                    nav.replaceTop(screen.copy(currentPath = newPath, entries = newEntries, selectedIndex = 0))
                }
            }
            else -> {}
        }
    }

    override fun onBack() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.DirectoryBrowser -> {
                val parent = setupCoordinator.parentDirectory(screen.currentPath)
                if (parent != null) {
                    val newEntries = setupCoordinator.listDirectories(parent)
                    nav.replaceTop(screen.copy(currentPath = parent, entries = newEntries, selectedIndex = 0))
                }
            }
            is LauncherScreen.Setup -> activityActions.finishAffinity()
            else -> {}
        }
    }

    override fun onStart() {
        val screen = nav.currentScreen as? LauncherScreen.Setup ?: return
        val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
        val continueEnabled = !isCustom || screen.customPath != null
        if (!continueEnabled) return
        val targetPath = if (isCustom) screen.customPath!!
            else screen.volumes[screen.volumeIndex].second + "Cannoli/"
        nav.replaceTop(LauncherScreen.Installing(targetPath = targetPath))
        onStartInstalling?.invoke(targetPath)
    }
}
