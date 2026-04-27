package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveTemplateHolderTest {

    private fun template(id: String) = DeviceTemplate(
        id = id,
        displayName = id,
        match = DeviceMatchRule(),
        bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
        source = TemplateSource.PADDLEBOAT_DB,
    )

    @Test
    fun starts_null() {
        assertNull(ActiveTemplateHolder().active.value)
    }

    @Test
    fun set_updates_state_flow_value() {
        val h = ActiveTemplateHolder()
        h.set(template("a"))
        assertEquals("a", h.active.value?.id)
    }

    @Test
    fun later_set_replaces_earlier_one_without_debounce() {
        val h = ActiveTemplateHolder()
        h.set(template("a"))
        h.set(template("b"))
        h.set(template("a"))
        assertEquals("a", h.active.value?.id)
    }

    @Test
    fun clear_resets_to_null() {
        val h = ActiveTemplateHolder()
        h.set(template("a"))
        h.clear()
        assertNull(h.active.value)
    }
}
