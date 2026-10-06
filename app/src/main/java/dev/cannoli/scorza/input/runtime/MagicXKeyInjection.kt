package dev.cannoli.scorza.input.runtime

import android.os.Build
import dev.cannoli.scorza.util.InputLog
import java.io.File

class MagicXKeyInjection(
    private val manufacturer: String = Build.MANUFACTURER.orEmpty(),
    private val node: File = File(NODE),
    private val log: (String) -> Unit = InputLog::write,
) {

    fun suppress() {
        if (!manufacturer.equals(MAKER, ignoreCase = true)) return
        if (!node.isFile) return
        val mode = runCatching { node.readText() }.getOrNull()?.substringAfter('=')?.trim() ?: return
        if (mode == PURE_GAMEPAD) return
        val wrote = runCatching { node.writeText(PURE_GAMEPAD) }.isSuccess
        log("magicx key injection: mode $mode -> ${if (wrote) PURE_GAMEPAD else "refused"}")
    }

    companion object {
        private const val NODE = "/sys/devices/platform/10010000.kp/keycodetype"
        private const val MAKER = "MagicX"
        private const val PURE_GAMEPAD = "2"
    }
}
