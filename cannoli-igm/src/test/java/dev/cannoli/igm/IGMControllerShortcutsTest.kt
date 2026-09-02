package dev.cannoli.igm

import dev.cannoli.core.SaveSlotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bridge holding this visit's edits apart from the table they layer over, as the real one does.
 *
 * Absent from [staged] is a row this visit never touched, so [inherited] answers for it; present
 * and empty is a row it cleared, which is this scope saying the action is off and not deferring.
 */
private class StagingBridge : FakeRetroArchBridge() {
    val staged = LinkedHashMap<ShortcutAction, Set<Int>>()
    val inherited = mutableMapOf<ShortcutAction, Set<Int>>()

    override fun shortcutBindings(): List<RetroArchBridge.ShortcutBinding> =
        ShortcutAction.entries.map { action ->
            val chord = staged[action] ?: inherited[action].orEmpty()
            RetroArchBridge.ShortcutBinding(action, chord)
        }

    override fun setShortcutBinding(action: ShortcutAction, chord: Set<Int>) {
        staged[action] = chord
    }
}

private const val CONFIRM = 96
private const val NORTH = 100
private const val DOWN = 20

class IGMControllerShortcutsTest {

    private fun controller(bridge: StagingBridge) = IGMController(
        bridge, "Game", SaveSlotStore(NO_SAVES),
        CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    ).also { it.binding.useTickerForTest(NoTicker) }

    /** No thread behind the hold, so a test can commit it directly. */
    private object NoTicker : BindingTicker {
        override fun post(delayMs: Long, action: Runnable) {}
        override fun cancel(action: Runnable) {}
    }

    private fun IGMController.bind(vararg keys: Int) {
        handleKeyDown(CONFIRM)
        keys.forEach { handleKeyDown(it) }
        binding.forceCommitForTest()
        keys.forEach { handleKeyUp(it) }
    }

    private fun IGMController.row(i: Int) = shortcutRows.value[i]

    private val first = ShortcutAction.entries[0]
    private val second = ShortcutAction.entries[1]

    // The defect that started this: binding one row and then another lost the first.
    @Test fun `binding several rows keeps every one of them`() {
        val bridge = StagingBridge()
        val c = controller(bridge)
        c.openShortcuts()

        c.bind(102, 103)
        c.handleKeyDown(DOWN)
        c.bind(104)

        assertEquals(setOf(102, 103), bridge.staged[first])
        assertEquals(setOf(104), bridge.staged[second])
    }

    @Test fun `an untouched row shows the chord it inherits and stages nothing`() {
        val bridge = StagingBridge().apply { inherited[first] = setOf(104, 105) }
        val c = controller(bridge)
        c.openShortcuts()

        assertEquals(setOf(104, 105), c.row(0).chord)
        assertTrue("untouched rows must keep following the table above", bridge.staged.isEmpty())
    }

    @Test fun `a row with nothing anywhere shows no chord`() {
        val c = controller(StagingBridge())
        c.openShortcuts()
        assertTrue(c.row(0).chord.isEmpty())
    }

    @Test fun `a bound chord replaces what the row inherited`() {
        val bridge = StagingBridge().apply { inherited[first] = setOf(104) }
        val c = controller(bridge)
        c.openShortcuts()

        c.bind(102, 103)

        assertEquals(setOf(102, 103), c.row(0).chord)
    }

    // The defect this design settles: a chord coming from the global table would not clear.
    @Test fun `clear takes away a chord the row only inherited`() {
        val bridge = StagingBridge().apply { inherited[first] = setOf(104) }
        val c = controller(bridge)
        c.openShortcuts()

        c.handleKeyDown(NORTH)

        assertEquals("cleared is this scope saying off, not saying nothing", emptySet<Int>(), bridge.staged[first])
        assertTrue("so the row shows no chord", c.row(0).chord.isEmpty())
    }

    @Test fun `clear takes away a chord bound in this same visit`() {
        val c = controller(StagingBridge())
        c.openShortcuts()
        c.bind(102, 103)

        c.handleKeyDown(NORTH)

        assertTrue(c.row(0).chord.isEmpty())
    }

    // Otherwise leaving Settings asks where to save a change that changes nothing.
    @Test fun `clear on a row that already shows nothing stages no change`() {
        val bridge = StagingBridge()
        val c = controller(bridge)
        c.openShortcuts()

        c.handleKeyDown(NORTH)

        assertFalse(bridge.staged.containsKey(first))
    }

    @Test fun `clear leaves the row it is not on alone`() {
        val bridge = StagingBridge().apply { inherited[second] = setOf(104) }
        val c = controller(bridge)
        c.openShortcuts()

        c.handleKeyDown(NORTH)

        assertEquals(setOf(104), c.row(1).chord)
    }

    // A pad repeats key downs while held, so the tail of a chord must not become navigation.
    @Test fun `keys held through a commit do not reach the list`() {
        val bridge = StagingBridge().apply { inherited[first] = setOf(104) }
        val c = controller(bridge)
        c.openShortcuts()

        c.handleKeyDown(CONFIRM)
        c.handleKeyDown(102)
        c.handleKeyDown(NORTH)
        c.binding.forceCommitForTest()
        // Still held, still repeating.
        c.handleKeyDown(NORTH)

        assertEquals("the chord must not have cleared itself", setOf(102, NORTH), bridge.staged[first])
    }
}
