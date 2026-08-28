package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaValueCyclerTest {

    /** Labels are deliberately not the machine values: cycling must never return one. */
    private fun setting(
        type: RaSettingType,
        value: String,
        min: Float? = null,
        max: Float? = null,
        step: Float? = null,
        options: List<String>? = null,
    ) = RaSetting(
        key = "key",
        label = "Label",
        type = type,
        machineValue = MachineValue(value),
        displayValue = value.uppercase(),
        min = min,
        max = max,
        step = step,
        options = options?.map { RaOption(MachineValue(it), it.uppercase()) },
    )

    @Test
    fun boolToggles() {
        assertEquals("false", RaValueCycler.next(setting(RaSettingType.BOOL, "true"), 1)?.raw)
        assertEquals("true", RaValueCycler.next(setting(RaSettingType.BOOL, "false"), -1)?.raw)
    }

    @Test
    fun enumWrapsForward() {
        val s = setting(RaSettingType.ENUM, "c", options = listOf("a", "b", "c"))
        assertEquals("a", RaValueCycler.next(s, 1)?.raw)
    }

    @Test
    fun enumWrapsBackward() {
        val s = setting(RaSettingType.ENUM, "a", options = listOf("a", "b", "c"))
        assertEquals("c", RaValueCycler.next(s, -1)?.raw)
    }

    @Test
    fun enumWithUnknownCurrentStartsAtFirst() {
        val s = setting(RaSettingType.ENUM, "zz", options = listOf("a", "b"))
        assertEquals("a", RaValueCycler.next(s, 1)?.raw)
    }

    @Test
    fun intStepsByStep() {
        val s = setting(RaSettingType.INT, "2", min = 0f, max = 10f, step = 2f)
        assertEquals("4", RaValueCycler.next(s, 1)?.raw)
        assertEquals("0", RaValueCycler.next(setting(RaSettingType.INT, "2", 0f, 10f, 2f), -1)?.raw)
    }

    @Test
    fun intClampsAtBounds() {
        assertEquals("10", RaValueCycler.next(setting(RaSettingType.INT, "10", 0f, 10f, 1f), 1)?.raw)
        assertEquals("0", RaValueCycler.next(setting(RaSettingType.INT, "0", 0f, 10f, 1f), -1)?.raw)
    }

    @Test
    fun intDefaultsStepToOne() {
        assertEquals("3", RaValueCycler.next(setting(RaSettingType.INT, "2", 0f, 10f), 1)?.raw)
    }

    @Test
    fun floatStepsAndFormatsCleanly() {
        val s = setting(RaSettingType.FLOAT, "0.5", min = 0f, max = 1f, step = 0.25f)
        assertEquals("0.75", RaValueCycler.next(s, 1)?.raw)
        assertEquals("1", RaValueCycler.next(setting(RaSettingType.FLOAT, "0.75", 0f, 1f, 0.25f), 1)?.raw)
    }

    @Test
    fun floatClampsAtBounds() {
        assertEquals("1", RaValueCycler.next(setting(RaSettingType.FLOAT, "1", 0f, 1f, 0.25f), 1)?.raw)
        assertEquals("0", RaValueCycler.next(setting(RaSettingType.FLOAT, "0", 0f, 1f, 0.25f), -1)?.raw)
    }

    @Test
    fun stringRoReturnsNull() {
        assertNull(RaValueCycler.next(setting(RaSettingType.STRING_RO, "anything"), 1)?.raw)
    }
}
