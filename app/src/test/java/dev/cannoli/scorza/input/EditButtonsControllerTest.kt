package dev.cannoli.scorza.input

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.MappingSource
import dev.cannoli.scorza.input.v2.repo.MappingRepository
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
            portRouter = dev.cannoli.scorza.input.v2.runtime.PortRouter(),
            activeMappingHolder = dev.cannoli.scorza.input.v2.runtime.ActiveMappingHolder(),
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
}
