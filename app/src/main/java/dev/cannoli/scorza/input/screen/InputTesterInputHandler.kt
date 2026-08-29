package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.NavigationController
import javax.inject.Inject

@ActivityScoped
class InputTesterInputHandler @Inject constructor(
    private val nav: NavigationController,
) : ScreenInputHandler {
    override fun onBack() = nav.pop()
}
