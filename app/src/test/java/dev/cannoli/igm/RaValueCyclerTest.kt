package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaValueCyclerTest {

    private fun setting(
        type: RaSettingType,
        value: String,
        min: Float? = null,
        max: Float? = null,
        step: Float? = null,
        options: List<String>? = null,
    ) = RaSetting("key", "Label", type, value, min, max, step, options)

    @Test
    fun boolToggles() {
        assertEquals("false", RaValueCycler.next(setting(RaSettingType.BOOL, "true"), 1))
        assertEquals("true", RaValueCycler.next(setting(RaSettingType.BOOL, "false"), -1))
    }

    @Test
    fun enumWrapsForward() {
        val s = setting(RaSettingType.ENUM, "c", options = listOf("a", "b", "c"))
        assertEquals("a", RaValueCycler.next(s, 1))
    }

    @Test
    fun enumWrapsBackward() {
        val s = setting(RaSettingType.ENUM, "a", options = listOf("a", "b", "c"))
        assertEquals("c", RaValueCycler.next(s, -1))
    }

    @Test
    fun enumWithUnknownCurrentStartsAtFirst() {
        val s = setting(RaSettingType.ENUM, "zz", options = listOf("a", "b"))
        assertEquals("a", RaValueCycler.next(s, 1))
    }

    @Test
    fun intStepsByStep() {
        val s = setting(RaSettingType.INT, "2", min = 0f, max = 10f, step = 2f)
        assertEquals("4", RaValueCycler.next(s, 1))
        assertEquals("0", RaValueCycler.next(setting(RaSettingType.INT, "2", 0f, 10f, 2f), -1))
    }

    @Test
    fun intClampsAtBounds() {
        assertEquals("10", RaValueCycler.next(setting(RaSettingType.INT, "10", 0f, 10f, 1f), 1))
        assertEquals("0", RaValueCycler.next(setting(RaSettingType.INT, "0", 0f, 10f, 1f), -1))
    }

    @Test
    fun intDefaultsStepToOne() {
        assertEquals("3", RaValueCycler.next(setting(RaSettingType.INT, "2", 0f, 10f), 1))
    }

    @Test
    fun floatStepsAndFormatsCleanly() {
        val s = setting(RaSettingType.FLOAT, "0.5", min = 0f, max = 1f, step = 0.25f)
        assertEquals("0.75", RaValueCycler.next(s, 1))
        assertEquals("1", RaValueCycler.next(setting(RaSettingType.FLOAT, "0.75", 0f, 1f, 0.25f), 1))
    }

    @Test
    fun floatClampsAtBounds() {
        assertEquals("1", RaValueCycler.next(setting(RaSettingType.FLOAT, "1", 0f, 1f, 0.25f), 1))
        assertEquals("0", RaValueCycler.next(setting(RaSettingType.FLOAT, "0", 0f, 1f, 0.25f), -1))
    }

    @Test
    fun stringRoReturnsNull() {
        assertNull(RaValueCycler.next(setting(RaSettingType.STRING_RO, "anything"), 1))
    }
}
