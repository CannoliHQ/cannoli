package dev.cannoli.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StateSlotPathsTest {
    private val base = "/sd/States/Game/Game.state"

    @Test
    fun autoSlotPaths() {
        assertEquals("$base.auto", StateSlotPaths.statePath(base, 0))
        assertEquals("$base.auto.png", StateSlotPaths.thumbnailPath(base, 0))
    }

    @Test
    fun firstManualSlotPaths() {
        assertEquals(base, StateSlotPaths.statePath(base, 1))
        assertEquals("$base.png", StateSlotPaths.thumbnailPath(base, 1))
    }

    @Test
    fun higherManualSlotPaths() {
        assertEquals("${base}2", StateSlotPaths.statePath(base, 3))
        assertEquals("${base}2.png", StateSlotPaths.thumbnailPath(base, 3))
    }

    @Test
    fun retroArchStateSlotForManualSlots() {
        assertEquals(0, StateSlotPaths.retroArchStateSlot(1))
        assertEquals(1, StateSlotPaths.retroArchStateSlot(2))
        assertEquals(9, StateSlotPaths.retroArchStateSlot(10))
    }
}
