package dev.cannoli.scorza.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.flow.MutableStateFlow

class NavigationController {
    val screenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)
    val currentScreen: LauncherScreen get() = screenStack.last()
    val dialogState = MutableStateFlow<DialogState>(DialogState.None)

    var resumableGames by mutableStateOf(emptySet<String>())
    var osdMessage by mutableStateOf<String?>(null)
    var navigating = false
    var currentFirstVisible = 0
    var currentPageSize = 10
    var pendingRecentlyPlayedReorder = false
    var lastKeyRepeatCount: Int = 0

    fun push(screen: LauncherScreen) { screenStack.add(screen) }

    fun pop() {
        if (screenStack.size > 1) screenStack.removeAt(screenStack.lastIndex)
    }

    fun replaceTop(screen: LauncherScreen) {
        if (screenStack.isNotEmpty()) screenStack[screenStack.lastIndex] = screen
    }
}
