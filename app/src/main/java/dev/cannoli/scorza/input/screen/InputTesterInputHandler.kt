package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.NavigationController

class InputTesterInputHandler(
    private val nav: NavigationController,
    private val controller: InputTesterController,
) : ScreenInputHandler {
    override fun onBack() = nav.pop()
}
