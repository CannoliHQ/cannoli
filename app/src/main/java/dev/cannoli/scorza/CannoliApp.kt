package dev.cannoli.scorza

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.cannoli.scorza.di.AppFonts
import javax.inject.Inject

@HiltAndroidApp
class CannoliApp : Application() {
    @Inject lateinit var appFonts: AppFonts

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.cannoli.scorza.i18n.LocaleOverride.wrap(base))
    }
}
