package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideDisplaysTest {

    private fun candidate(
        id: Int,
        isValid: Boolean = true,
        isOff: Boolean = false,
        isPrivate: Boolean = false,
    ) = DisplayCandidate(id, isValid, isOff, isPrivate)

    @Test
    fun noSecondDisplay() {
        assertNull(GuideDisplays.select(listOf(candidate(0))))
    }

    @Test
    fun picksTheNonDefaultDisplay() {
        assertEquals(4, GuideDisplays.select(listOf(candidate(0), candidate(4))))
    }

    @Test
    fun ignoresPrivateVirtualDisplays() {
        assertNull(GuideDisplays.select(listOf(candidate(0), candidate(2, isPrivate = true))))
    }

    @Test
    fun ignoresDisplaysThatAreOff() {
        assertNull(GuideDisplays.select(listOf(candidate(0), candidate(4, isOff = true))))
    }

    @Test
    fun ignoresInvalidDisplays() {
        assertNull(GuideDisplays.select(listOf(candidate(0), candidate(4, isValid = false))))
    }

    @Test
    fun picksLowestIdWhenSeveralQualify() {
        val chosen = GuideDisplays.select(listOf(candidate(0), candidate(7), candidate(4)))
        assertEquals(4, chosen)
    }

    @Test
    fun skipsAPrivateDisplayToReachARealOne() {
        val chosen = GuideDisplays.select(
            listOf(candidate(0), candidate(2, isPrivate = true), candidate(4))
        )
        assertEquals(4, chosen)
    }

    @Test
    fun emptyListIsNull() {
        assertNull(GuideDisplays.select(emptyList()))
    }
}
