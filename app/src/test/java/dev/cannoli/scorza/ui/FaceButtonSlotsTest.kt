package dev.cannoli.scorza.ui

import dev.cannoli.ui.components.FaceLabels
import dev.cannoli.ui.components.faceButtonSlots
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceButtonSlotsTest {

    private val four = FaceLabels(top = "X", bottom = "B", left = "Y", right = "A")
    private val six = four.copy(c = "C", z = "Z")

    @Test fun `four labels keep the diamond`() {
        val slots = faceButtonSlots(four)
        assertEquals(listOf("btn_north", "btn_south", "btn_west", "btn_east"), slots.map { it.name })
        assertEquals(listOf(0f to -5f, 0f to 5f, -5f to 0f, 5f to 0f), slots.map { it.dx to it.dy })
    }

    @Test fun `six labels draw X Y Z over A B C`() {
        val slots = faceButtonSlots(six)
        val top = slots.filter { it.dy < 0 }.sortedBy { it.dx }
        val bottom = slots.filter { it.dy > 0 }.sortedBy { it.dx }
        assertEquals(listOf("X", "Y", "Z"), top.map { it.label })
        assertEquals(listOf("btn_north", "btn_west", "btn_r3"), top.map { it.name })
        assertEquals(listOf("A", "B", "C"), bottom.map { it.label })
        assertEquals(listOf("btn_east", "btn_south", "btn_l3"), bottom.map { it.name })
    }

    @Test fun `one of C or Z alone is not six buttons`() {
        assertEquals(4, faceButtonSlots(four.copy(c = "C")).size)
    }
}
