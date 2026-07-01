package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommVariantDataTest {
    private fun dto(id: Int, siblingIds: List<Int>, main: Boolean = false) = SimpleRomDto(
        id = id,
        platformId = 1,
        fsName = "game-$id.zip",
        siblings = siblingIds.map { RomSiblingDto(it) },
        isMainSibling = main,
    )

    @Test fun `group key is the minimum id across self and siblings`() {
        assertEquals(7, dto(id = 42, siblingIds = listOf(7, 88)).toDomain().groupKey)
        assertEquals(42, dto(id = 42, siblingIds = listOf(88, 91)).toDomain().groupKey)
    }

    @Test fun `a game with no siblings groups on its own id`() {
        assertEquals(42, dto(id = 42, siblingIds = emptyList()).toDomain().groupKey)
    }

    @Test fun `main sibling flag is carried through`() {
        assertEquals(true, dto(id = 1, siblingIds = listOf(2), main = true).toDomain().isMainSibling)
        assertEquals(false, dto(id = 2, siblingIds = listOf(1)).toDomain().isMainSibling)
    }
}
