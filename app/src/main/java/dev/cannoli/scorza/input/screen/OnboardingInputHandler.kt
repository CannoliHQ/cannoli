package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.navigation.OnboardingPermission
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

    var onRequestPermission: ((OnboardingPermission) -> Unit)? = null
    var onFolderChosen: ((targetPath: String) -> Unit)? = null

    override fun onUp() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> nav.replaceTop(screen.moved(-1))
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
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        if (screen.isStorageRowFocused) nav.replaceTop(screen.cycledVolume(-1))
    }

    override fun onRight() {
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        if (screen.isStorageRowFocused) nav.replaceTop(screen.cycledVolume(1))
    }

    override fun onConfirm() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> {
                val perm = screen.focusedPermission
                when {
                    screen.isContinueRowFocused -> screen.targetPath?.let { onFolderChosen?.invoke(it) }
                    screen.isStorageRowFocused && screen.isCustomVolume && screen.customPath == null -> {
                        val entries = setupCoordinator.listDirectories("/storage/")
                        nav.push(LauncherScreen.DirectoryBrowser(
                            purpose = BrowsePurpose.SETUP,
                            currentPath = "/storage/",
                            entries = entries
                        ))
                    }
                    perm != null && !screen.isFocusedGranted -> onRequestPermission?.invoke(perm)
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
                            val wizardIdx = nav.screenStack.indexOfLast { it is LauncherScreen.OnboardingPermissions }
                            if (wizardIdx >= 0) {
                                val wizard = nav.screenStack[wizardIdx] as LauncherScreen.OnboardingPermissions
                                val path = if (resolved.endsWith("/")) resolved else "$resolved/"
                                nav.screenStack[wizardIdx] = wizard.copy(customPath = path)
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
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        val target = screen.targetPath ?: return
        onFolderChosen?.invoke(target)
    }
}
