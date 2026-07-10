package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommVariantFolderTest {
    @Test fun `region rank prefers USA and world, then europe, then japan`() {
        assertEquals(0, RommVariantFolder.regionRank(listOf("USA")))
        assertEquals(0, RommVariantFolder.regionRank(listOf("World")))
        assertEquals(1, RommVariantFolder.regionRank(listOf("Europe")))
        assertEquals(2, RommVariantFolder.regionRank(listOf("Japan")))
        assertEquals(3, RommVariantFolder.regionRank(listOf("Korea")))
        assertEquals(3, RommVariantFolder.regionRank(emptyList()))
    }
}
