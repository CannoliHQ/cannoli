package dev.cannoli.scorza.input

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.repo.MappingRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditButtonsControllerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var repo: MappingRepository
    private lateinit var controller: EditButtonsController
    private var clockMs = 0L

    @Before fun setup() {
        repo = MappingRepository(tmp.newFolder("templates"))
        controller = EditButtonsController(
            repository = repo,
            portRouter = dev.cannoli.scorza.input.runtime.PortRouter(),
            activeMappingHolder = dev.cannoli.scorza.input.runtime.ActiveMappingHolder(),
        ).also { it.clock = { clockMs } }
    }

    private fun emptyTemplate(): DeviceMapping = DeviceMapping(
        id = "test", displayName = "Test",
        match = DeviceMatchRule(name = "Test"),
        bindings = emptyMap(),
        source = MappingSource.USER_WIZARD,
    )

    @Test fun `start then key press finalizes after 500ms with Button binding`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 0
        controller.captureRawKeyEvent(96)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized template")
        assertEquals(listOf(InputBinding.Button(96)), finalized.bindings[CanonicalButton.BTN_SOUTH])
        assertTrue(finalized.userEdited)
        assertNotNull(repo.findById("test"))
    }

    @Test fun `multiple sources within window produce one binding per source`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_UP)
        clockMs = 0
        controller.captureRawKeyEvent(19)
        clockMs = 100
        controller.captureRawAxisEvent(mapOf(16 to -1f))
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        val bindings = finalized.bindings[CanonicalButton.BTN_UP] ?: emptyList()
        assertEquals(2, bindings.size)
        assertTrue(bindings.any { it is InputBinding.Button && it.keyCode == 19 })
        assertTrue(bindings.any { it is InputBinding.Hat && it.axis == 16 })
    }

    @Test fun `5s timeout cancels without saving`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 5001
        val result = controller.tickAndMaybeFinalize()
        assertNull(result)
        assertNull(repo.findById("test"))
    }

    @Test fun `cancelListening discards pending events`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        controller.captureRawKeyEvent(96)
        controller.cancelListening()
        clockMs = 1000
        assertNull(controller.tickAndMaybeFinalize())
        assertFalse(controller.isListening)
    }

    @Test fun `binding a key already used by another canonical swaps their bindings`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(
                CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
                CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
            ),
        )
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 0
        controller.captureRawKeyEvent(97)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(listOf(InputBinding.Button(97)), finalized.bindings[CanonicalButton.BTN_SOUTH])
        assertEquals(listOf(InputBinding.Button(96)), finalized.bindings[CanonicalButton.BTN_EAST])
    }

    @Test fun `binding a key used by another canonical clears the other when new slot was empty`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97))),
        )
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 0
        controller.captureRawKeyEvent(97)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(listOf(InputBinding.Button(97)), finalized.bindings[CanonicalButton.BTN_SOUTH])
        assertEquals(emptyList<InputBinding>(), finalized.bindings[CanonicalButton.BTN_EAST])
    }

    @Test fun `swap removes only conflicting input from previous owner with multi-bind`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(
                CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
                CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97), InputBinding.Button(98)),
            ),
        )
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 0
        controller.captureRawKeyEvent(97)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(listOf(InputBinding.Button(97)), finalized.bindings[CanonicalButton.BTN_SOUTH])
        val east = finalized.bindings[CanonicalButton.BTN_EAST] ?: emptyList()
        assertTrue("expected old south binding restored to east", east.contains(InputBinding.Button(96)))
        assertTrue("expected non-conflicting binding preserved", east.contains(InputBinding.Button(98)))
        assertFalse("conflicting key 97 should be gone from east", east.contains(InputBinding.Button(97)))
    }

    @Test fun `existing bindings on other canonicals are preserved`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97))),
        )
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 0
        controller.captureRawKeyEvent(96)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(listOf(InputBinding.Button(97)), finalized.bindings[CanonicalButton.BTN_EAST])
        assertEquals(listOf(InputBinding.Button(96)), finalized.bindings[CanonicalButton.BTN_SOUTH])
    }

    @Test fun `digital axis capture does not displace existing analog stick binding on the same axis`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(
                CanonicalButton.BTN_L3 to listOf(
                    InputBinding.Axis(
                        axis = 0,
                        restingValue = 0f,
                        activeMin = -1f,
                        activeMax = 1f,
                        digitalThreshold = 0.5f,
                        analogRole = AnalogRole.LEFT_STICK_X,
                    ),
                ),
            ),
        )
        controller.startListening(template, CanonicalButton.BTN_RIGHT)
        clockMs = 0
        controller.captureRawAxisEvent(mapOf(0 to 1f))
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")

        val right = finalized.bindings[CanonicalButton.BTN_RIGHT] ?: emptyList()
        assertEquals(1, right.size)
        val rightAxis = right.first() as InputBinding.Axis
        assertEquals(0, rightAxis.axis)
        assertEquals(AnalogRole.DIGITAL_BUTTON, rightAxis.analogRole)

        val l3 = finalized.bindings[CanonicalButton.BTN_L3] ?: emptyList()
        assertEquals(1, l3.size)
        val l3Axis = l3.first() as InputBinding.Axis
        assertEquals(AnalogRole.LEFT_STICK_X, l3Axis.analogRole)
    }

    @Test fun `binding edit promotes source from ANDROID_DEFAULT to USER_WIZARD`() {
        // ANDROID_DEFAULT-sourced mappings are bumped to tier 3 in MappingResolver so a
        // bundled RA cfg can win for the device. When the user actually customizes a button
        // binding, the resulting saved mapping must promote to USER_WIZARD so it wins tier 1
        // and the user's customization is never silently replaced.
        val template = emptyTemplate().copy(source = MappingSource.ANDROID_DEFAULT)
        controller.startListening(template, CanonicalButton.BTN_SOUTH)
        clockMs = 0
        controller.captureRawKeyEvent(96)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(MappingSource.USER_WIZARD, finalized.source)
        assertTrue(finalized.userEdited)
    }
}
