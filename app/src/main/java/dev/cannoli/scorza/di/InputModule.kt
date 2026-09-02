package dev.cannoli.scorza.di

import android.app.Activity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.ui.components.OsdController

@Module
@InstallIn(ActivityComponent::class)
object InputModule {

    @Provides @ActivityScoped
    fun provideOsdController(): OsdController = OsdController()

    // Lives in cannoli-igm so the in-game menu can hold one too, where this graph does not reach.
    @Provides @ActivityScoped
    fun provideBindingController(): dev.cannoli.igm.BindingController =
        dev.cannoli.igm.BindingController()

    @Provides @ActivityScoped
    fun provideInputTesterController(
        activity: Activity,
        viewModel: InputTesterViewModel,
        portRouter: dev.cannoli.scorza.input.runtime.PortRouter,
        activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder,
    ): InputTesterController = InputTesterController(
        viewModel = viewModel,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        unknownDeviceName = activity.getString(R.string.input_tester_device_unknown),
        keyboardDeviceName = activity.getString(R.string.input_tester_device_keyboard),
    )
}
