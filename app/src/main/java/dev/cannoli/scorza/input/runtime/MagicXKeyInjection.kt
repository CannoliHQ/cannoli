package dev.cannoli.scorza.input.runtime

import android.os.Build
import dev.cannoli.scorza.util.InputLog
import java.io.File

/**
 * Stops a MagicX handheld's keypad driver sending a second Android key beside the face buttons.
 *
 * At the driver's default, one press of A puts both BTN_GAMEPAD and KEY_SELECT on the wire, which
 * Android delivers as BUTTON_A and DPAD_CENTER. Anything counting presses sees one press of two
 * different buttons, which is what made the first-run wizard impossible to finish: the confirm run
 * resets on the second keycode and the pips never fill.
 *
 * The driver takes three modes, recovered by reverse-engineering the stock launcher, which writes
 * the same node per app as its "Input Enhancement" setting: 0 sends select with A, 1 sends select
 * with A and back with B, 2 sends neither. The value lives in kernel memory rather than on disk, so
 * it returns to 0 on every boot and has to be written again.
 *
 * Nothing is put back. Cannoli is paused while a game holds the screen, which is exactly when the
 * pad most needs to be only a pad, and the value clears itself at the next boot anyway.
 */
class MagicXKeyInjection(
    private val manufacturer: String = Build.MANUFACTURER.orEmpty(),
    private val node: File = File(NODE),
    private val log: (String) -> Unit = InputLog::write,
) {

    /**
     * Asked for on every resume rather than once: another app can write the same node, and the
     * value has to be true again by the time the player presses anything.
     */
    fun suppress() {
        if (!manufacturer.equals(MAKER, ignoreCase = true)) return
        if (!node.isFile) return
        // Reads back as "type=N" and is written as the bare number.
        val mode = runCatching { node.readText() }.getOrNull()?.substringAfter('=')?.trim() ?: return
        if (mode == PURE_GAMEPAD) return
        val wrote = runCatching { node.writeText(PURE_GAMEPAD) }.isSuccess
        // A device that refuses the write is left alone. Every other handheld runs without this,
        // and a player cannot act on being told their kernel said no.
        log("magicx key injection: mode $mode -> ${if (wrote) PURE_GAMEPAD else "refused"}")
    }

    companion object {
        private const val NODE = "/sys/devices/platform/10010000.kp/keycodetype"
        private const val MAKER = "MagicX"
        private const val PURE_GAMEPAD = "2"
    }
}
