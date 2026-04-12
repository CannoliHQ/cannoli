package dev.cannoli.scorza.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTesterViewModelTest {

    private var fakeNow = 1_000L
    private fun vm() = InputTesterViewModel(now = { fakeNow })

    @Test
    fun keyDown_addsButtonAndLogsEvent() {
        val vm = vm()
        vm.onKeyDown(
            port = 0,
            keyCode = 96,
            keyName = "BUTTON_A",
            deviceId = 10,
            deviceName = "Test Pad",
            resolvedButton = "btn_a",
        )
        val state = vm.state.value
        assertTrue("btn_a" in (state.portStates[0]?.pressedButtons ?: emptySet()))
        assertEquals(1, state.eventLog.size)
        assertEquals("btn_a", state.eventLog.first().resolvedButton)
        assertEquals(0, state.lastEventDevice?.port)
        assertEquals("Test Pad", state.lastEventDevice?.name)
    }

    @Test
    fun keyUp_removesButton() {
        val vm = vm()
        vm.onKeyDown(0, 96, "BUTTON_A", 10, "Test Pad", "btn_a")
        vm.onKeyUp(0, 96, "BUTTON_A", 10, "Test Pad", "btn_a")
        val pressed = vm.state.value.portStates[0]?.pressedButtons ?: emptySet()
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun unresolvedKey_stillLogsWithNullButton() {
        val vm = vm()
        vm.onKeyDown(0, 127, "F9", 10, "Test Pad", null)
        val entry = vm.state.value.eventLog.first()
        assertNull(entry.resolvedButton)
        assertEquals(127, entry.keyCode)
    }

    @Test
    fun eventLogIsBoundedByCapacity() {
        val vm = InputTesterViewModel(now = { fakeNow }, eventLogCapacity = 3)
        repeat(5) { i ->
            vm.onKeyDown(0, 96 + i, "K$i", 10, "Test Pad", "btn_a")
        }
        val log = vm.state.value.eventLog
        assertEquals(3, log.size)
        assertEquals("K4", log[0].keyName)
        assertEquals("K2", log[2].keyName)
    }

    @Test
    fun requestExit_setsFlag() {
        val vm = vm()
        assertFalse(vm.state.value.exitRequested)
        vm.requestExit()
        assertTrue(vm.state.value.exitRequested)
    }

    @Test
    fun orphanKeyUp_isIgnored() {
        val vm = vm()
        vm.onKeyUp(0, 96, "BUTTON_A", 10, "Test Pad", "btn_a")
        assertEquals(0, vm.state.value.eventLog.size)
    }

    @Test
    fun autorepeatKeyDown_notLoggedAndDoesNotCountTowardExit() {
        val vm = vm()
        vm.onKeyDown(0, 96, "BUTTON_A", 10, "Test Pad", "btn_a")
        repeat(10) {
            vm.onKeyDown(0, 96, "BUTTON_A", 10, "Test Pad", "btn_a")
        }
        assertEquals(1, vm.state.value.eventLog.size)
        assertFalse(vm.state.value.exitRequested)
        assertTrue("btn_a" in (vm.state.value.portStates[0]?.pressedButtons ?: emptySet()))
    }

    @Test
    fun onMotion_setsStickAndTriggerValues() {
        val vm = vm()
        vm.onMotion(
            port = 0,
            deviceId = 10,
            deviceName = "Test Pad",
            leftX = 0.5f, leftY = -0.25f,
            rightX = 0f, rightY = 0f,
            leftTrigger = 0.75f,
            rightTrigger = 0f,
            hatX = 0f, hatY = 0f,
        )
        val s = vm.state.value.portStates[0]!!
        assertEquals(0.5f, s.leftStick.x, 0.0001f)
        assertEquals(-0.25f, s.leftStick.y, 0.0001f)
        assertEquals(0.75f, s.leftTrigger, 0.0001f)
    }

    @Test
    fun hatAxis_addsDpadButtonWithoutKeycode() {
        val vm = vm()
        vm.onMotion(0, 10, "Test Pad", 0f, 0f, 0f, 0f, 0f, 0f, hatX = -1f, hatY = 0f)
        val pressed = vm.state.value.portStates[0]?.pressedButtons ?: emptySet()
        assertTrue("btn_left" in pressed)
    }

    @Test
    fun triggerAxisAbove05_addsShoulderButton() {
        val vm = vm()
        vm.onMotion(0, 10, "Test Pad", 0f, 0f, 0f, 0f, leftTrigger = 0.6f, rightTrigger = 0f, 0f, 0f)
        val pressed = vm.state.value.portStates[0]?.pressedButtons ?: emptySet()
        assertTrue("btn_l2" in pressed)
    }

    @Test
    fun setConnectedPorts_updatesStateAndActivePortStaysValid() {
        val vm = vm()
        vm.setConnectedPorts(listOf(
            DeviceInfo(0, 10, "Pad A"),
            DeviceInfo(1, 11, "Pad B"),
        ))
        assertEquals(2, vm.state.value.connectedPorts.size)
        assertEquals(0, vm.state.value.activePort)
    }

    @Test
    fun cycleActivePort_movesToNextConnectedPort() {
        val vm = vm()
        vm.setConnectedPorts(listOf(
            DeviceInfo(0, 10, "A"),
            DeviceInfo(2, 12, "C"),
        ))
        vm.cycleActivePort(forward = true)
        assertEquals(2, vm.state.value.activePort)
        vm.cycleActivePort(forward = true)
        assertEquals(0, vm.state.value.activePort)
    }

    @Test
    fun hatAxisReleased_clearsDpadButton() {
        val vm = vm()
        vm.onMotion(0, 10, "Test Pad", 0f, 0f, 0f, 0f, 0f, 0f, hatX = -1f, hatY = 0f)
        assertTrue("btn_left" in (vm.state.value.portStates[0]?.pressedButtons ?: emptySet()))
        vm.onMotion(0, 10, "Test Pad", 0f, 0f, 0f, 0f, 0f, 0f, hatX = 0f, hatY = 0f)
        assertFalse("btn_left" in (vm.state.value.portStates[0]?.pressedButtons ?: emptySet()))
    }

    @Test
    fun setConnectedPorts_reselectsWhenActivePortDisconnects() {
        val vm = vm()
        vm.setConnectedPorts(listOf(
            DeviceInfo(0, 10, "A"),
            DeviceInfo(1, 11, "B"),
        ))
        vm.cycleActivePort(forward = true)
        assertEquals(1, vm.state.value.activePort)
        vm.setConnectedPorts(listOf(DeviceInfo(2, 12, "C")))
        assertEquals(2, vm.state.value.activePort)
    }

    @Test
    fun cycleActivePort_backwardWraps() {
        val vm = vm()
        vm.setConnectedPorts(listOf(
            DeviceInfo(0, 10, "A"),
            DeviceInfo(2, 12, "C"),
        ))
        vm.cycleActivePort(forward = false)
        assertEquals(2, vm.state.value.activePort)
        vm.cycleActivePort(forward = false)
        assertEquals(0, vm.state.value.activePort)
    }
}
