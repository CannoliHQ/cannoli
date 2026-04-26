package dev.cannoli.scorza.di

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.BindingController
import dev.cannoli.scorza.input.ControllerManager
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.ProfileManager
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InputSingletonModule {

    @Provides @Singleton
    fun provideControllerManager(): ControllerManager = ControllerManager()
}

@Module
@InstallIn(ActivityComponent::class)
object InputModule {

    @Provides @ActivityScoped
    fun provideInputHandler(
        nav: NavigationController,
        profileManager: ProfileManager,
    ): InputHandler = InputHandler(
        getButtonMappings = {
            val screen = nav.currentScreen as? LauncherScreen.ControlBinding
            if (screen != null && screen.profileName == ProfileManager.NAVIGATION) screen.controls
            else profileManager.readControls(ProfileManager.NAVIGATION)
        }
    )

    @Provides @ActivityScoped
    fun provideBindingController(
        nav: NavigationController,
        inputHandler: InputHandler,
        @ApplicationContext context: Context,
    ): BindingController {
        val osdHandler = Handler(Looper.getMainLooper())
        val clearOsd = Runnable { nav.osdMessage = null }
        return BindingController(
            nav = nav,
            controlButtons = LibretroInput().buttons,
            swapConfirmBackProvider = { inputHandler.swapConfirmBack },
            showOsd = { text, durationMs ->
                osdHandler.removeCallbacks(clearOsd)
                nav.osdMessage = text
                osdHandler.postDelayed(clearOsd, durationMs)
            },
            cannotStealConfirmText = context.getString(R.string.osd_cannot_steal_confirm),
        )
    }

    @Provides @ActivityScoped
    fun provideInputTesterController(
        activity: Activity,
        viewModel: InputTesterViewModel,
        controllerManager: ControllerManager,
        profileManager: ProfileManager,
        inputHandler: InputHandler,
    ): InputTesterController = InputTesterController(
        viewModel = viewModel,
        controllerManager = controllerManager,
        profileManager = profileManager,
        inputHandler = inputHandler,
        unknownDeviceName = activity.getString(R.string.input_tester_device_unknown),
        keyboardDeviceName = activity.getString(R.string.input_tester_device_keyboard),
    )
}
