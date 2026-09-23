package dev.cannoli.ricotta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeldCommandsParityTest {
    private val bridge = File("jni/ricotta_bridge.c").readText()
    private val header = File("jni/ricotta_osd.h").readText()
    private val patch = File("../patches/retroarch.patch").readText()

    private fun body(signature: String): String {
        val start = bridge.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        return bridge.substring(start, bridge.indexOf("\n}\n", start))
    }

    @Test fun `command_event asks the bridge before running anything`() {
        assertTrue(patch.contains("+   if (ricotta_hold_command((int)cmd, data))"))
        assertTrue(header.contains("int  ricotta_hold_command(int cmd, void *data);"))
    }

    @Test fun `hardcore is never held`() {
        assertFalse(body("static int ricotta_deferrable(int cmd)").contains("CMD_EVENT_CHEEVOS_HARDCORE_MODE_TOGGLE"))
    }

    @Test fun `held commands run before the game resumes, resets, or opens RetroArch's menu`() {
        for (native in listOf("nativeUnpause(", "nativeReset(", "nativeMenuToggle(")) {
            val b = body("Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_$native")
            val flush = b.indexOf("RICOTTA_QCMD_HELD_FLUSH")
            assertTrue("$native must flush", flush >= 0)
            assertTrue("$native must flush first", flush < b.indexOf("CMD_EVENT_"))
        }
    }

    @Test fun `held commands are dropped when the game quits`() {
        val b = body("Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeQuit(")
        assertTrue(b.indexOf("RICOTTA_QCMD_HELD_DROP") in 0 until b.indexOf("CMD_EVENT_QUIT"))
    }

    @Test fun `held commands can also be dropped on demand`() {
        assertTrue(body("Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeDropHeld(")
            .contains("RICOTTA_QCMD_HELD_DROP"))
    }

    @Test fun `held commands can also be flushed on demand`() {
        assertTrue(body("Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeFlushHeld(")
            .contains("RICOTTA_QCMD_HELD_FLUSH"))
    }

    @Test fun `the drain loop checks the held branches before the fall-through`() {
        val b = body("void ricotta_bridge_poll_commands(void)")
        val flush = b.indexOf("if (entry.cmd == RICOTTA_QCMD_HELD_FLUSH)")
        assertTrue("must check RICOTTA_QCMD_HELD_FLUSH", flush >= 0)
        assertTrue("must check it before the fall-through",
            flush < b.indexOf("command_event(entry.cmd, NULL);"))
    }

    @Test fun `only a menu write holds`() {
        assertTrue(body("static void ricotta_ra_apply(const char *key, const char *value, int token, const char *watch)\n{").contains("g_hold_active = token != 0;"))
    }

    @Test fun `audio start and stop cancel each other out rather than both firing`() {
        val b = body("static int ricotta_opposite(int cmd)")
        assertTrue(b.contains("CMD_EVENT_AUDIO_START"))
        assertTrue(b.contains("CMD_EVENT_AUDIO_STOP"))
    }
}
