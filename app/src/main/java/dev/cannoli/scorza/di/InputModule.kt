package dev.cannoli.scorza.di

import android.app.Activity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.BindingController
import dev.cannoli.scorza.input.ControllerManager
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel

@Module
@InstallIn(ActivityComponent::class)
object InputModule {

    @Provides @ActivityScoped
    fun provideControllerManager(): ControllerManager = ControllerManager()

    @Provides @ActivityScoped
    fun provideBindingController(
        nav: NavigationController,
    ): BindingController = BindingController(nav = nav)

    @Provides @ActivityScoped
    fun provideInputTesterController(
        activity: Activity,
        viewModel: InputTesterViewModel,
        controllerManager: ControllerManager,
        portRouter: dev.cannoli.scorza.input.v2.runtime.PortRouter,
    ): InputTesterController = InputTesterController(
        viewModel = viewModel,
        controllerManager = controllerManager,
        portRouter = portRouter,
        unknownDeviceName = activity.getString(R.string.input_tester_device_unknown),
        keyboardDeviceName = activity.getString(R.string.input_tester_device_keyboard),
    )
}
