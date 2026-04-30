package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController

class BindingController(
    private val nav: NavigationController,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val shortcutHoldMs = 1500
    private val shortcutTickMs = 100L

    private val shortcutCountdownRunnable = object : Runnable {
        override fun run() {
            val screen = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return
            if (!screen.listening) return
            val newMs = screen.countdownMs + shortcutTickMs.toInt()
            if (newMs >= shortcutHoldMs) {
                val action = ShortcutAction.entries.getOrNull(screen.selectedIndex) ?: return
                val chord = screen.heldKeys
                val cleared = screen.shortcuts.filterValues { it != chord }
                nav.replaceTop(screen.copy(
                    shortcuts = cleared + (action to chord),
                    listening = false, heldKeys = emptySet(), countdownMs = 0
                ))
            } else {
                nav.replaceTop(screen.copy(countdownMs = newMs))
                handler.postDelayed(this, shortcutTickMs)
            }
        }
    }

    fun cancelShortcutListening() {
        handler.removeCallbacks(shortcutCountdownRunnable)
        val screen = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return
        if (screen.listening) {
            nav.replaceTop(screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
        }
    }

    fun handleKeyDown(keyCode: Int): Boolean {
        val screen = nav.currentScreen
        when (screen) {
            is LauncherScreen.ShortcutBinding -> {
                if (!screen.listening) return false
                if (screen.heldKeys.contains(keyCode)) return true
                val newKeys = screen.heldKeys + keyCode
                nav.replaceTop(screen.copy(heldKeys = newKeys, countdownMs = 0))
                handler.removeCallbacks(shortcutCountdownRunnable)
                handler.postDelayed(shortcutCountdownRunnable, shortcutTickMs)
                return true
            }
            else -> return false
        }
    }
}
