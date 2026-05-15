package dev.cannoli.scorza.input.v2.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiblingFolderTest {

    private fun gp(id: Int, name: String, desc: String = "desc-$id") =
        SiblingFolder.Candidate(id, name, desc, isGamepad = true)

    private fun sib(id: Int, name: String, desc: String = "desc-$id") =
        SiblingFolder.Candidate(id, name, desc, isGamepad = false)

    @Test fun single_gamepad_yields_one_cluster_with_no_aliases() {
        val out = SiblingFolder.fold(listOf(gp(5, "8BitDo Lite gamepad")))
        assertEquals(1, out.size)
        assertEquals(5, out[0].gamepad.androidDeviceId)
        assertTrue(out[0].aliases.isEmpty())
        assertEquals("desc-5", out[0].persistenceDescriptor)
    }

    @Test fun retroid_pattern_gamepad_name_is_prefix_of_siblings() {
        val out = SiblingFolder.fold(listOf(
            sib(10, "DualSense Wireless Controller Motion Sensors", "ds-motion-A"),
            sib(11, "DualSense Wireless Controller Touchpad", "ds-touch-A"),
            gp(12, "DualSense Wireless Controller", ""),
        ))
        assertEquals(1, out.size)
        assertEquals(12, out[0].gamepad.androidDeviceId)
        assertEquals(setOf(10, 11), out[0].aliases.map { it.androidDeviceId }.toSet())
        // Falls back to a sibling's descriptor because the gamepad's own is empty.
        assertTrue(out[0].persistenceDescriptor in setOf("ds-motion-A", "ds-touch-A"))
    }

    @Test fun one35_pattern_gamepad_is_one_of_several_siblings() {
        // None of the names is a prefix of the others; they share "One35 Virtual" as a common
        // prefix root. The gamepad is "One35 Virtual Gamepad", not the shortest name.
        val out = SiblingFolder.fold(listOf(
            sib(3, "One35 Virtual Keyboard"),
            sib(4, "One35 Virtual Mouse"),
            gp(5, "One35 Virtual Gamepad"),
        ))
        assertEquals(1, out.size)
        assertEquals(5, out[0].gamepad.androidDeviceId)
        assertEquals(setOf(3, 4), out[0].aliases.map { it.androidDeviceId }.toSet())
    }

    @Test fun id_adjacency_separates_two_same_model_clusters() {
        val out = SiblingFolder.fold(listOf(
            sib(10, "DualSense Wireless Controller Motion Sensors", "A-motion"),
            sib(11, "DualSense Wireless Controller Touchpad", "A-touch"),
            gp(12, "DualSense Wireless Controller", ""),
            sib(13, "DualSense Wireless Controller Motion Sensors", "B-motion"),
            sib(14, "DualSense Wireless Controller Touchpad", "B-touch"),
            gp(15, "DualSense Wireless Controller", ""),
        ))
        assertEquals(2, out.size)
        val byGamepad = out.associateBy { it.gamepad.androidDeviceId }
        assertEquals(setOf(10, 11), byGamepad.getValue(12).aliases.map { it.androidDeviceId }.toSet())
        assertEquals(setOf(13, 14), byGamepad.getValue(15).aliases.map { it.androidDeviceId }.toSet())
        // Each cluster picks a sibling descriptor, and the two clusters' descriptors differ.
        assertTrue(byGamepad.getValue(12).persistenceDescriptor != byGamepad.getValue(15).persistenceDescriptor)
    }

    @Test fun non_adjacent_devices_with_same_prefix_are_not_merged() {
        // Two distinct same-model pads with a large id gap (one is e.g. the only DualSense
        // currently attached; the other was attached, disconnected, and re-attached much later
        // taking a far-away id). They should stay as two clusters.
        val out = SiblingFolder.fold(listOf(
            gp(5, "DualSense Wireless Controller"),
            gp(20, "DualSense Wireless Controller"),
        ))
        assertEquals(2, out.size)
    }

    @Test fun candidates_without_gamepad_sibling_are_dropped() {
        // Only the keyboard + mouse arrive (no gamepad endpoint). Nothing useful for port routing.
        val out = SiblingFolder.fold(listOf(
            sib(3, "One35 Virtual Keyboard"),
            sib(4, "One35 Virtual Mouse"),
        ))
        assertTrue(out.isEmpty())
    }

    @Test fun unrelated_devices_do_not_merge_even_when_id_adjacent() {
        val out = SiblingFolder.fold(listOf(
            gp(5, "8BitDo Lite gamepad"),
            sib(6, "USB Headset Audio"),
        ))
        assertEquals(1, out.size)
        assertTrue(out[0].aliases.isEmpty())
    }
}
