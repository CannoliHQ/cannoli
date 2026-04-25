package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.navigation.LauncherScreen

class BindingController(
    private val screenStack: SnapshotStateList<LauncherScreen>,
    private val controlButtons: List<LibretroInput.ButtonDef>,
    private val swapConfirmBackProvider: () -> Boolean,
    private val showOsd: (String, Long) -> Unit,
    private val cannotStealConfirmText: String,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val shortcutHoldMs = 1500
    private val shortcutTickMs = 100L

    private val controlListenTimeoutMs = 3000
    private val controlListenTickMs = 100L

    private val shortcutCountdownRunnable = object : Runnable {
        override fun run() {
            val screen = screenStack.lastOrNull() as? LauncherScreen.ShortcutBinding ?: return
            if (!screen.listening) return
            val newMs = screen.countdownMs + shortcutTickMs.toInt()
            if (newMs >= shortcutHoldMs) {
                val action = ShortcutAction.entries.getOrNull(screen.selectedIndex) ?: return
                val chord = screen.heldKeys
                val cleared = screen.shortcuts.filterValues { it != chord }
                screenStack[screenStack.lastIndex] = screen.copy(
                    shortcuts = cleared + (action to chord),
                    listening = false, heldKeys = emptySet(), countdownMs = 0
                )
            } else {
                screenStack[screenStack.lastIndex] = screen.copy(countdownMs = newMs)
                handler.postDelayed(this, shortcutTickMs)
            }
        }
    }

    private val controlListenRunnable = object : Runnable {
        override fun run() {
            val screen = screenStack.lastOrNull() as? LauncherScreen.ControlBinding ?: return
            if (screen.listeningIndex < 0) return
            val newMs = screen.listenCountdownMs + controlListenTickMs.toInt()
            if (newMs >= controlListenTimeoutMs) {
                screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = -1, listenCountdownMs = 0)
            } else {
                screenStack[screenStack.lastIndex] = screen.copy(listenCountdownMs = newMs)
                handler.postDelayed(this, controlListenTickMs)
            }
        }
    }

    fun cancelShortcutListening() {
        handler.removeCallbacks(shortcutCountdownRunnable)
        val screen = screenStack.lastOrNull() as? LauncherScreen.ShortcutBinding ?: return
        if (screen.listening) {
            screenStack[screenStack.lastIndex] = screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0)
        }
    }

    fun startControlListening() {
        val screen = screenStack.lastOrNull() as? LauncherScreen.ControlBinding ?: return
        if (screen.listeningIndex >= 0) return
        screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = screen.selectedIndex, listenCountdownMs = 0)
        handler.postDelayed(controlListenRunnable, controlListenTickMs)
    }

    fun handleKeyDown(keyCode: Int): Boolean {
        val screen = screenStack.lastOrNull() ?: return false
        when (screen) {
            is LauncherScreen.ControlBinding -> {
                if (screen.listeningIndex < 0 || screen.listeningIndex >= controlButtons.size) return false
                handler.removeCallbacks(controlListenRunnable)
                val btn = controlButtons[screen.listeningIndex]
                if (wouldStealNavConfirm(screen, btn.prefKey, keyCode)) {
                    showOsd(cannotStealConfirmText, 4500L)
                    screenStack[screenStack.lastIndex] = screen.copy(
                        listeningIndex = -1, listenCountdownMs = 0
                    )
                    return true
                }
                val newControls = screen.controls.toMutableMap()
                for (other in controlButtons) {
                    if (other.prefKey == btn.prefKey) continue
                    val current = newControls[other.prefKey] ?: other.defaultKeyCode
                    if (current == keyCode) newControls[other.prefKey] = LibretroInput.UNMAPPED
                }
                newControls[btn.prefKey] = keyCode
                screenStack[screenStack.lastIndex] = screen.copy(
                    controls = newControls,
                    listeningIndex = -1, listenCountdownMs = 0
                )
                return true
            }
            is LauncherScreen.ShortcutBinding -> {
                if (!screen.listening) return false
                if (screen.heldKeys.contains(keyCode)) return true
                val newKeys = screen.heldKeys + keyCode
                screenStack[screenStack.lastIndex] = screen.copy(heldKeys = newKeys, countdownMs = 0)
                handler.removeCallbacks(shortcutCountdownRunnable)
                handler.postDelayed(shortcutCountdownRunnable, shortcutTickMs)
                return true
            }
            else -> return false
        }
    }

    private fun wouldStealNavConfirm(screen: LauncherScreen.ControlBinding, prefKey: String, keyCode: Int): Boolean {
        if (screen.profileName != ProfileManager.NAVIGATION) return false
        val confirmKey = if (swapConfirmBackProvider()) "btn_east" else "btn_south"
        if (prefKey == confirmKey) return false
        val default = controlButtons.first { it.prefKey == confirmKey }.defaultKeyCode
        val current = screen.controls[confirmKey] ?: default
        return current == keyCode
    }
}
