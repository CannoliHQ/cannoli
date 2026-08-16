package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.DialogState
import javax.inject.Inject

@ActivityScoped
class DirectoryBrowserInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val launcherActions: LauncherActions,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.DirectoryBrowser? =
        nav.currentScreen as? LauncherScreen.DirectoryBrowser

    override fun onUp() = move(-1)

    override fun onDown() = move(1)

    override fun onConfirm() {
        val screen = current() ?: return
        val hasSelect = screen.currentPath != "/storage/"
        if (hasSelect && screen.selectedIndex == 0) {
            val resolved = if (setupCoordinator.isVolumeRoot(screen.currentPath)) {
                screen.currentPath + "Cannoli/"
            } else {
                screen.currentPath
            }
            when (screen.purpose) {
                BrowsePurpose.SETUP -> {
                    val stepIdx = nav.screenStack.indexOfLast { it is LauncherScreen.OnboardingStorage }
                    if (stepIdx >= 0) {
                        val step = nav.screenStack[stepIdx] as LauncherScreen.OnboardingStorage
                        val path = if (resolved.endsWith("/")) resolved else "$resolved/"
                        nav.screenStack[stepIdx] = step.copy(customPath = path)
                    }
                    nav.pop()
                }
                BrowsePurpose.SD_ROOT -> {
                    settings.sdCardRoot = resolved
                    nav.pop()
                    nav.dialogState.value = DialogState.RestartRequired
                }
                BrowsePurpose.ROM_DIRECTORY -> {
                    nav.pop()
                    launcherActions.confirmRomDirectoryChange(resolved)
                }
            }
        } else {
            val entryIdx = screen.selectedIndex - if (hasSelect) 1 else 0
            val folderName = screen.entries.getOrNull(entryIdx) ?: return
            val newPath = setupCoordinator.resolveDirectoryEntry(screen.currentPath, folderName)
            val newEntries = setupCoordinator.listDirectories(newPath)
            nav.replaceTop(screen.copy(currentPath = newPath, entries = newEntries, selectedIndex = 0))
        }
    }

    override fun onBack() {
        val screen = current() ?: return
        val parent = setupCoordinator.parentDirectory(screen.currentPath)
        if (parent != null) {
            val newEntries = setupCoordinator.listDirectories(parent)
            nav.replaceTop(screen.copy(currentPath = parent, entries = newEntries, selectedIndex = 0))
        } else if (screen.purpose != BrowsePurpose.SETUP) {
            nav.pop()
        }
    }

    override fun onWest() {
        if (current() != null) nav.pop()
    }

    override fun onNorth() {
        val screen = current() ?: return
        if (screen.currentPath != "/storage/") {
            nav.dialogState.value = DialogState.NewFolderInput(parentPath = screen.currentPath)
        }
    }

    private fun move(delta: Int) {
        val screen = current() ?: return
        val hasSelect = screen.currentPath != "/storage/"
        val count = screen.entries.size + if (hasSelect) 1 else 0
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + delta).mod(count)))
    }
}
