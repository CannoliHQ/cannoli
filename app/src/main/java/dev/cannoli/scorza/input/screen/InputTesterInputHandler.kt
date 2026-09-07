package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.NavigationController
import javax.inject.Inject

@ActivityScoped
class InputTesterInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val controller: InputTesterController,
) : ScreenInputHandler {
    // Back leaves the tester as surely as the start + select hold does, so it has to tear down the
    // same things. Popping on its own left the evaluators holding whatever the tester put in them.
    override fun onBack() {
        controller.exit()
        nav.pop()
    }
}
