package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagonalLockTest {

    private val up = RetroJoypad.RETRO_UP
    private val down = RetroJoypad.RETRO_DOWN
    private val left = RetroJoypad.RETRO_LEFT
    private val right = RetroJoypad.RETRO_RIGHT
    private val a = RetroJoypad.RETRO_A
    private val b = RetroJoypad.RETRO_B

    private val allow = true
    private val block = false

    private fun lock() = DiagonalLock()

    @Test fun `allowing diagonals passes a diagonal through untouched`() {
        val l = lock()
        assertEquals(up or left, l.filter(0, up or left, allow))
    }

    @Test fun `the held axis keeps the core when the perpendicular arrives`() {
        val l = lock()
        assertEquals(up, l.filter(0, up, block))
        assertEquals(up, l.filter(0, up or left, block))
    }

    @Test fun `the still-held perpendicular takes over on release`() {
        val l = lock()
        l.filter(0, up, block)
        l.filter(0, up or left, block)
        assertEquals(left, l.filter(0, left, block))
        assertEquals(0, l.filter(0, 0, block))
    }

    @Test fun `a diagonal arriving from rest resolves to neutral`() {
        val l = lock()
        assertEquals(0, l.filter(0, up or left, block))
    }

    @Test fun `the latch works normally once a from-rest diagonal resolves to one axis`() {
        val l = lock()
        l.filter(0, up or left, block)
        assertEquals(left, l.filter(0, left, block))
        assertEquals(left, l.filter(0, up or left, block))
    }

    @Test fun `releasing everything re-arms the from-rest rule`() {
        val l = lock()
        l.filter(0, up, block)
        l.filter(0, 0, block)
        assertEquals(0, l.filter(0, up or left, block))
    }

    @Test fun `same-axis opposition is not a diagonal and passes through`() {
        val l = lock()
        assertEquals(left or right, l.filter(0, left or right, block))
        assertEquals(up or down, l.filter(0, up or down, block))
    }

    @Test fun `a button remapped onto a direction participates in resolution`() {
        val l = lock()
        l.filter(0, left, block)
        assertEquals(left, l.filter(0, left or up, block))
    }

    @Test fun `a mask with no direction bits is untouched`() {
        val l = lock()
        assertEquals(a or b, l.filter(0, a or b, block))
    }

    @Test fun `non-direction bits survive alongside a filtered diagonal`() {
        val l = lock()
        l.filter(0, up, block)
        assertEquals(up or a, l.filter(0, up or left or a, block))
    }

    @Test fun `latches are per port`() {
        val l = lock()
        l.filter(0, up, block)
        l.filter(1, left, block)
        assertEquals(up, l.filter(0, up or left, block))
        assertEquals(left, l.filter(1, up or left, block))
    }

    @Test fun `reset clears the latch so a held diagonal re-runs the from-rest rule`() {
        val l = lock()
        l.filter(0, up, block)
        l.reset()
        assertEquals(0, l.filter(0, up or left, block))
    }
}
