package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.util.ErrorLog
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.romm.sync.DEFAULT_SLOT
import dev.cannoli.scorza.romm.sync.SlotManager
import dev.cannoli.ui.components.KeyboardState
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class SaveSlotsInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val slotManager: SlotManager,
    @IoScope private val ioScope: CoroutineScope,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.SaveSlots? =
        nav.currentScreen as? LauncherScreen.SaveSlots

    override fun onUp() {
        val s = current() ?: return
        if (s.slots.isEmpty()) return
        val next = (s.selectedIndex - 1).mod(s.slots.size)
        nav.replaceTop(s.copy(selectedIndex = next))
    }

    override fun onDown() {
        val s = current() ?: return
        if (s.slots.isEmpty()) return
        val next = (s.selectedIndex + 1).mod(s.slots.size)
        nav.replaceTop(s.copy(selectedIndex = next))
    }

    override fun onConfirm() {
        val s = current() ?: return
        if (s.pendingDelete) {
            val target = s.slots.getOrNull(s.selectedIndex) ?: return
            nav.replaceTop(s.copy(pendingDelete = false))
            ioScope.launch {
                runCatching { slotManager.delete(s.gameKey, s.romId, target.slot) }
                    .onFailure { ErrorLog.write("save slot delete failed: ${it.message}") }
                val refreshed = slotManager.listSlots(s.gameKey, s.romId)
                withContext(Dispatchers.Main) {
                    val current = nav.currentScreen as? LauncherScreen.SaveSlots ?: return@withContext
                    nav.replaceTop(current.copy(
                        slots = refreshed,
                        selectedIndex = current.selectedIndex.coerceIn(0, (refreshed.size - 1).coerceAtLeast(0)),
                    ))
                }
            }
            return
        }
        val target = s.slots.getOrNull(s.selectedIndex) ?: return
        ioScope.launch {
            runCatching {
                slotManager.switch(s.gameKey, s.tag, s.base, s.romId, s.emulator, target.slot)
            }.onFailure { ErrorLog.write("save slot switch failed: ${it.message}") }
            val refreshed = slotManager.listSlots(s.gameKey, s.romId)
            withContext(Dispatchers.Main) {
                val current = nav.currentScreen as? LauncherScreen.SaveSlots ?: return@withContext
                nav.replaceTop(current.copy(
                    slots = refreshed,
                    selectedIndex = current.selectedIndex.coerceIn(0, (refreshed.size - 1).coerceAtLeast(0)),
                ))
            }
        }
    }

    override fun onBack() {
        val s = current() ?: return
        if (s.pendingDelete) {
            nav.replaceTop(s.copy(pendingDelete = false))
            return
        }
        nav.pop()
    }

    override fun onNorth() {
        val s = current() ?: return
        if (s.pendingDelete) return
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "save_slot_create",
            keyboard = KeyboardState(),
        )
    }

    override fun onWest() {
        val s = current() ?: return
        if (s.pendingDelete) return
        val target = s.slots.getOrNull(s.selectedIndex) ?: return
        if (target.slot == DEFAULT_SLOT) return
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "save_slot_rename:${target.slot}",
            keyboard = KeyboardState(text = target.slot, cursorPos = target.slot.length),
        )
    }

    override fun onL1() {
        val s = current() ?: return
        if (s.pendingDelete) return
        val target = s.slots.getOrNull(s.selectedIndex) ?: return
        if (target.slot == DEFAULT_SLOT) return
        nav.replaceTop(s.copy(pendingDelete = true))
    }

    fun refreshSlots() {
        val s = current() ?: return
        ioScope.launch {
            val refreshed = slotManager.listSlots(s.gameKey, s.romId)
            withContext(Dispatchers.Main) {
                val current = nav.currentScreen as? LauncherScreen.SaveSlots ?: return@withContext
                nav.replaceTop(current.copy(
                    slots = refreshed,
                    selectedIndex = current.selectedIndex.coerceIn(0, (refreshed.size - 1).coerceAtLeast(0)),
                ))
            }
        }
    }
}
