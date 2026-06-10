package dev.cannoli.scorza.romm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommModelsTest {

    private fun page(total: Int, offset: Int, count: Int) =
        RommPage(items = List(count) { it }, total = total, limit = 100, offset = offset)

    @Test fun `hasMore is true when more items remain beyond this page`() {
        assertTrue(page(total = 250, offset = 0, count = 100).hasMore)
        assertTrue(page(total = 250, offset = 100, count = 100).hasMore)
    }

    @Test fun `hasMore is false on the last page`() {
        assertFalse(page(total = 250, offset = 200, count = 50).hasMore)
    }

    @Test fun `hasMore is false when empty`() {
        assertFalse(page(total = 0, offset = 0, count = 0).hasMore)
    }
}
