package dev.cannoli.scorza.input

import dev.cannoli.scorza.input.screen.GameListInputHandler
import dev.cannoli.scorza.input.screen.InputTesterInputHandler
import dev.cannoli.scorza.input.screen.SettingsInputHandler
import dev.cannoli.scorza.input.screen.SetupInputHandler
import dev.cannoli.scorza.input.screen.SystemListInputHandler
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController

class InputRouter(
    private val nav: NavigationController,
    private val dialogHandler: DialogInputHandler,
    private val systemListHandler: SystemListInputHandler,
    private val gameListHandler: GameListInputHandler,
    private val settingsHandler: SettingsInputHandler,
    private val setupHandler: SetupInputHandler,
    private val inputTesterHandler: InputTesterInputHandler,
    private val scrollListHandlerFor: (LauncherScreen) -> ScreenInputHandler,
) {
    fun wire(inputHandler: InputHandler) {
        inputHandler.onUp    = { if (!dialogHandler.onUp())     currentHandler().onUp() }
        inputHandler.onDown  = { if (!dialogHandler.onDown())   currentHandler().onDown() }
        inputHandler.onLeft  = { if (!dialogHandler.onLeft())   currentHandler().onLeft() }
        inputHandler.onRight = { if (!dialogHandler.onRight())  currentHandler().onRight() }
        inputHandler.onConfirm = { if (!dialogHandler.onConfirm()) currentHandler().onConfirm() }
        inputHandler.onBack  = { if (!dialogHandler.onBack())   currentHandler().onBack() }
        inputHandler.onStart = { if (!dialogHandler.onStart())  currentHandler().onStart() }
        inputHandler.onSelect = { if (!dialogHandler.onSelect()) currentHandler().onSelect() }
        inputHandler.onNorth = { if (!dialogHandler.onNorth())  currentHandler().onNorth() }
        inputHandler.onWest  = { if (!dialogHandler.onWest())   currentHandler().onWest() }
        inputHandler.onL1    = { if (!dialogHandler.onL1())     currentHandler().onL1() }
        inputHandler.onR1    = { if (!dialogHandler.onR1())     currentHandler().onR1() }
        inputHandler.onL2    = { if (!dialogHandler.onL2())     currentHandler().onL2() }
        inputHandler.onR2    = { if (!dialogHandler.onR2())     currentHandler().onR2() }
    }

    fun onSelectUp() {
        dialogHandler.cancelSelectHold()
        gameListHandler.cancelSelectHoldTimer()

        val dialogConsumed = dialogHandler.onSelectUp()
        if (!dialogConsumed) currentHandler().onSelectUp()

        dialogHandler.selectDown = false
        dialogHandler.selectHeld = false
    }

    private fun currentHandler(): ScreenInputHandler = when (val screen = nav.currentScreen) {
        LauncherScreen.SystemList -> systemListHandler
        LauncherScreen.GameList   -> gameListHandler
        LauncherScreen.Settings   -> settingsHandler
        is LauncherScreen.Setup,
        is LauncherScreen.Installing,
        is LauncherScreen.Housekeeping -> setupHandler
        is LauncherScreen.DirectoryBrowser -> setupHandler
        LauncherScreen.InputTester -> inputTesterHandler
        else -> scrollListHandlerFor(screen)
    }
}
