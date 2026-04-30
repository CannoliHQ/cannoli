package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveMappingHolderTest {

    private fun template(id: String) = DeviceMapping(
        id = id,
        displayName = id,
        match = DeviceMatchRule(),
        bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
        source = MappingSource.RETROARCH_AUTOCONFIG,
    )

    @Test
    fun starts_null() {
        assertNull(ActiveMappingHolder().active.value)
    }

    @Test
    fun set_updates_state_flow_value() {
        val h = ActiveMappingHolder()
        h.set(template("a"))
        assertEquals("a", h.active.value?.id)
    }

    @Test
    fun later_set_replaces_earlier_one_without_debounce() {
        val h = ActiveMappingHolder()
        h.set(template("a"))
        h.set(template("b"))
        h.set(template("a"))
        assertEquals("a", h.active.value?.id)
    }

    @Test
    fun clear_resets_to_null() {
        val h = ActiveMappingHolder()
        h.set(template("a"))
        h.clear()
        assertNull(h.active.value)
    }
}
