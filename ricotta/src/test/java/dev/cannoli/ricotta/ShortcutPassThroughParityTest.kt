package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A pass-through chord, START held on its own to open the menu, must leave START with the game.
 * These pin the native branch that does that, since nothing on the Kotlin side can see it.
 */
class ShortcutPassThroughParityTest {
    private val bridge = File("jni/ricotta_bridge.c").readText()

    private fun body(signature: String): String {
        val start = bridge.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        return bridge.substring(start, bridge.indexOf("\n}\n", start))
    }

    private val intercept get() = body("int ricotta_bridge_intercept_key(int keycode, int action)")

    private fun passThroughBranch(): String {
        val b = intercept
        val start = b.indexOf("&& g_chords[best].pass_through && g_chords[best].hold_ms > 0)")
        assertTrue("missing the pass-through match branch", start >= 0)
        return b.substring(start, b.indexOf("return 0;", start) + "return 0;".length)
    }

    @Test fun `the table carries the flag between the hold and the key count`() {
        val b = body("Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetShortcutChords(")
        val hold = b.indexOf("int hold_ms      = (int)elems[at++];")
        val pass = b.indexOf("int pass_through = (int)elems[at++];")
        val count = b.indexOf("int count        = (int)elems[at++];")
        assertTrue(hold in 0 until pass && pass < count)
        assertTrue(b.contains("g_chords[chords].pass_through = pass_through != 0;"))
    }

    @Test fun `a pass-through match takes nothing from the game`() {
        val branch = passThroughBranch()
        assertFalse(branch.contains("ricotta_request_retract"))
        assertFalse(branch.contains("ricotta_mark_swallowed"))
        assertTrue(branch.trimEnd().endsWith("return 0;"))
    }

    @Test fun `a pass-through match arms the hold`() {
        val branch = passThroughBranch()
        assertTrue(branch.contains("g_hold_chord = best;"))
        assertTrue(branch.contains("g_hold_deadline_us = ricotta_now_us()"))
        assertTrue(branch.contains("RICOTTA_ACT_HOLD_ARMED"))
        assertTrue(branch.contains("g_firing_chord = best;"))
    }

    @Test fun `the pass-through branch is checked before the one that retracts`() {
        val b = intercept
        assertTrue(
            b.indexOf("g_chords[best].pass_through && g_chords[best].hold_ms > 0") <
                b.indexOf("ricotta_request_retract(g_chords[best].keys[i]);")
        )
    }

    @Test fun `letting go before the deadline cancels the hold`() {
        val b = intercept
        val release = b.indexOf("ricotta_held_remove(keycode);")
        val cancel = b.indexOf("RICOTTA_ACT_HOLD_CANCELLED", release)
        assertTrue(release >= 0 && cancel > release)
        assertTrue(b.substring(release, cancel).contains("if (g_hold_chord == g_firing_chord)"))
    }

    @Test fun `a fired pass-through chord lets its release reach the game even under the menu`() {
        val pump = body("void ricotta_bridge_poll_commands(void)")
        assertTrue(pump.contains("if (c->pass_through)"))
        assertTrue(pump.contains("g_passthrough_up[g_passthrough_up_count++] = c->keys[i];"))
        val b = intercept
        val visible = b.indexOf("if (g_igm_visible)")
        val letThrough = b.indexOf("if (action == 1 && ricotta_take_passthrough_up(keycode))")
        assertTrue(visible >= 0 && letThrough > visible)
        assertEquals("return 0;", b.substring(letThrough).lines()[1].trim())
    }

    @Test fun `opening the menu keeps the releases it still owes the game`() {
        val b = body("Java_dev_cannoli_ricotta_EmbeddedRetroArchBridge_nativeSetIGMVisible(")
        assertFalse(b.contains("g_passthrough_up_count"))
    }
}
