package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.OnboardingPermission
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.DialogState
import javax.inject.Inject

@ActivityScoped
class OnboardingInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val activityActions: ActivityActions,
    private val launcherActions: LauncherActions,
) : ScreenInputHandler {

    var onStartInstalling: ((targetPath: String) -> Unit)? = null
    var onInstallFinished: (() -> Unit)? = null
    var onRequestPermission: ((OnboardingPermission) -> Unit)? = null
    var onContinue: (() -> Unit)? = null

    override fun onUp() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> nav.replaceTop(screen.moved(-1))
            is LauncherScreen.Setup ->
                nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).coerceAtLeast(0)))
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                val count = screen.entries.size + if (hasSelect) 1 else 0
                if (count > 0) {
                    val newIdx = (screen.selectedIndex - 1 + count) % count
                    nav.replaceTop(screen.copy(selectedIndex = newIdx))
                }
            }
            else -> {}
        }
    }

    override fun onDown() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> nav.replaceTop(screen.moved(1))
            is LauncherScreen.Setup -> {
                val maxIndex = if (screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom") 1 else 0
                nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).coerceAtMost(maxIndex)))
            }
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                val count = screen.entries.size + if (hasSelect) 1 else 0
                if (count > 0) {
                    val newIdx = (screen.selectedIndex + 1) % count
                    nav.replaceTop(screen.copy(selectedIndex = newIdx))
                }
            }
            else -> {}
        }
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
            is LauncherScreen.OnboardingPermissions ->
                if (!screen.isFocusedGranted) onRequestPermission?.invoke(screen.focusedPermission)
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
                    val cb = onInstallFinished
                    if (cb != null) cb() else activityActions.restartApp()
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                if (hasSelect && screen.selectedIndex == 0) {
                    val resolved = if (setupCoordinator.isVolumeRoot(screen.currentPath))
                        screen.currentPath + "Cannoli/"
                    else screen.currentPath
                    when (screen.purpose) {
                        BrowsePurpose.SETUP -> {
                            val setupIdx = nav.screenStack.indexOfLast { it is LauncherScreen.Setup }
                            if (setupIdx >= 0) {
                                val setup = nav.screenStack[setupIdx] as LauncherScreen.Setup
                                val path = if (resolved.endsWith("/")) resolved else "$resolved/"
                                nav.screenStack[setupIdx] = setup.copy(customPath = path)
                            }
                            nav.pop()
                        }
                        BrowsePurpose.SD_ROOT -> {
                            settings.sdCardRoot = resolved
                            nav.pop()
                            nav.dialogState.value = DialogState.RestartRequired
                        }
                        BrowsePurpose.ROM_DIRECTORY -> {
                            settings.romDirectory = resolved
                            launcherActions.invalidateAllLibraryCaches()
                            nav.pop()
                            nav.dialogState.value = DialogState.RestartRequired
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
            else -> {}
        }
    }

    override fun onBack() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> activityActions.finishAffinity()
            is LauncherScreen.DirectoryBrowser -> {
                val parent = setupCoordinator.parentDirectory(screen.currentPath)
                if (parent != null) {
                    val newEntries = setupCoordinator.listDirectories(parent)
                    nav.replaceTop(screen.copy(currentPath = parent, entries = newEntries, selectedIndex = 0))
                } else if (screen.purpose != BrowsePurpose.SETUP) {
                    nav.pop()
                }
            }
            is LauncherScreen.Setup -> activityActions.finishAffinity()
            else -> {}
        }
    }

    override fun onWest() {
        if (nav.currentScreen is LauncherScreen.DirectoryBrowser) {
            nav.pop()
        }
    }

    override fun onNorth() {
        val screen = nav.currentScreen as? LauncherScreen.DirectoryBrowser ?: return
        if (screen.currentPath != "/storage/") {
            nav.dialogState.value = DialogState.NewFolderInput(parentPath = screen.currentPath)
        }
    }

    override fun onStart() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> if (screen.allGranted) onContinue?.invoke()
            is LauncherScreen.Setup -> {
                val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
                val continueEnabled = !isCustom || screen.customPath != null
                if (!continueEnabled) return
                val targetPath = if (isCustom) screen.customPath!!
                    else screen.volumes[screen.volumeIndex].second + "Cannoli/"
                nav.replaceTop(LauncherScreen.Installing(targetPath = targetPath))
                onStartInstalling?.invoke(targetPath)
            }
            else -> {}
        }
    }
}
