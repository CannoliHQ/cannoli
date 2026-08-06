package dev.cannoli.scorza

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CannoliApp : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.cannoli.scorza.i18n.LocaleOverride.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        installLogFlushOnCrash()
    }

    // Log writes are queued, so a crash would otherwise take the tail of every log with it,
    // which is the part worth reading. Native crashes still bypass this; tombstones cover those.
    private fun installLogFlushOnCrash() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                dev.cannoli.scorza.util.ErrorLog.error("uncaught on ${thread.name}", error)
                dev.cannoli.scorza.util.LogWriter.flush(CRASH_FLUSH_TIMEOUT_MS)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private companion object {
        const val CRASH_FLUSH_TIMEOUT_MS = 2000L
    }
}
