package dev.cannoli.scorza.input.screen

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.permissions.AppPermission
import dev.cannoli.scorza.permissions.permissionStates
import dev.cannoli.scorza.ui.screens.DialogState
import javax.inject.Inject

@ActivityScoped
class PermissionsInputHandler @Inject constructor(
    private val nav: NavigationController,
    @ApplicationContext private val context: Context,
) : ScreenInputHandler {

    fun open() {
        nav.push(LauncherScreen.Permissions(states = permissionStates(context)))
    }

    /** Grants land as a return from the system settings page, so the page rereads on resume. */
    fun refresh() {
        val screen = current() ?: return
        nav.replaceTop(screen.copy(states = permissionStates(context)))
    }

    private fun current(): LauncherScreen.Permissions? =
        nav.currentScreen as? LauncherScreen.Permissions

    override fun onUp() {
        val screen = current() ?: return
        val count = AppPermission.entries.size
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).mod(count)))
    }

    override fun onDown() {
        val screen = current() ?: return
        val count = AppPermission.entries.size
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).mod(count)))
    }

    override fun onConfirm() {
        val screen = current() ?: return
        val permission = AppPermission.entries.getOrNull(screen.selectedIndex) ?: return
        nav.dialogState.value = DialogState.PermissionDetail(permission)
    }

    override fun onBack() {
        nav.pop()
    }
}
