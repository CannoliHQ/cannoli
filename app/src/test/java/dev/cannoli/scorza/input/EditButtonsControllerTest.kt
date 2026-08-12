package dev.cannoli.scorza.input

import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditButtonsControllerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var repo: AutoconfigRepository
    private lateinit var controller: EditButtonsController
    private var clockMs = 0L

    @Before fun setup() {
        val dir = tmp.newFolder("autoconfig")
        repo = AutoconfigRepository { dir }
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
        val entry = repo.findById("test") ?: error("expected a cfg on disk")
        assertTrue(entry.cannoliUser)
        assertEquals(96, entry.buttonBindings["b_btn"])
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
                        analogRole = AnalogRole.ANALOG_STICK,
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
        assertEquals(AnalogRole.ANALOG_STICK, l3Axis.analogRole)
    }

    @Test fun `a hat capture on the menu button leaves the mapping unchanged`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(CanonicalButton.BTN_MENU to listOf(InputBinding.Button(4))),
        )
        controller.startListening(template, CanonicalButton.BTN_MENU)
        clockMs = 0
        controller.captureRawAxisEvent(mapOf(16 to -1f))
        clockMs = 500
        assertNull(controller.tickAndMaybeFinalize())
        clockMs = 5001
        assertNull(controller.tickAndMaybeFinalize())
        assertNull(repo.findById("test"))
        assertEquals(listOf(InputBinding.Button(4)), template.bindings[CanonicalButton.BTN_MENU])
    }

    @Test fun `a key capture on the menu button still binds`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_MENU)
        clockMs = 0
        controller.captureRawKeyEvent(318)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(listOf(InputBinding.Button(318)), finalized.bindings[CanonicalButton.BTN_MENU])
    }

    @Test fun `stick capture produces the importer's bipolar Axis shape`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_LSTICK_X)
        clockMs = 0
        controller.captureRawAxisEvent(mapOf(0 to 1f))
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(
            listOf(
                InputBinding.Axis(0, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(0, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            finalized.bindings[CanonicalButton.BTN_LSTICK_X],
        )
    }

    @Test fun `stick capture uses only the dominant axis on a diagonal push`() {
        val template = emptyTemplate()
        controller.startListening(template, CanonicalButton.BTN_RSTICK_Y)
        clockMs = 0
        controller.captureRawAxisEvent(mapOf(2 to 0.7f, 3 to -1f))
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(
            listOf(
                InputBinding.Axis(3, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(3, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            finalized.bindings[CanonicalButton.BTN_RSTICK_Y],
        )
    }

    @Test fun `rebinding an L3 click leaves its stick canonical untouched`() {
        val template = emptyTemplate().copy(
            bindings = mapOf(
                CanonicalButton.BTN_L3 to listOf(InputBinding.Button(106)),
                CanonicalButton.BTN_LSTICK_X to listOf(
                    InputBinding.Axis(0, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                    InputBinding.Axis(0, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                ),
            ),
        )
        controller.startListening(template, CanonicalButton.BTN_L3)
        clockMs = 0
        controller.captureRawKeyEvent(200)
        clockMs = 500
        val finalized = controller.tickAndMaybeFinalize() ?: error("expected finalized")
        assertEquals(listOf(InputBinding.Button(200)), finalized.bindings[CanonicalButton.BTN_L3])
        assertEquals(
            listOf(
                InputBinding.Axis(0, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(0, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            finalized.bindings[CanonicalButton.BTN_LSTICK_X],
        )
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
