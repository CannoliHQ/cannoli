package dev.cannoli.core.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RaPendingUnlocksTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun queue(): RaPendingUnlocks = RaPendingUnlocks(File(tmp.root, "Pending"))

    private fun award(achievementId: Int, hardcore: Int = 0) =
        "r=awardachievement&u=player&t=abc&a=$achievementId&h=$hardcore&m=a1b2c3"

    @Test fun `an unlock is written and read back whole`() {
        val q = queue()
        assertTrue(q.write(award(7), gameId = 42, atMs = 1000L))
        val all = q.list()
        assertEquals(1, all.size)
        assertEquals(42, all[0].gameId)
        assertEquals(7, all[0].achievementId)
        assertEquals(1000L, all[0].atMs)
        assertEquals(award(7), all[0].body)
    }

    @Test fun `unlocks come back oldest first, so they submit in the order they happened`() {
        val q = queue()
        q.write(award(9), gameId = 42, atMs = 3000L)
        q.write(award(7), gameId = 42, atMs = 1000L)
        q.write(award(8), gameId = 42, atMs = 2000L)
        assertEquals(listOf(7, 8, 9), q.list().map { it.achievementId })
    }

    @Test fun `the same achievement twice is one file, because the second is the same fact`() {
        val q = queue()
        q.write(award(7), gameId = 42, atMs = 1000L)
        q.write(award(7), gameId = 42, atMs = 5000L)
        assertEquals(1, q.list().size)
    }

    @Test fun `a submitted unlock is deleted`() {
        val q = queue()
        q.write(award(7), gameId = 42, atMs = 1000L)
        val p = q.list().single()
        assertTrue(q.delete(p))
        assertTrue(q.list().isEmpty())
    }

    @Test fun `counts are per game`() {
        val q = queue()
        q.write(award(7), gameId = 42, atMs = 1000L)
        q.write(award(8), gameId = 42, atMs = 2000L)
        q.write(award(9), gameId = 43, atMs = 3000L)
        assertEquals(mapOf(42 to 2, 43 to 1), q.countByGame())
        assertEquals(setOf(7, 8), q.achievementIdsFor(42))
        assertEquals(emptySet<Int>(), q.achievementIdsFor(99))
    }

    @Test fun `a request that is not an award is refused`() {
        val q = queue()
        assertFalse(q.write("r=ping&u=player&t=abc", gameId = 42, atMs = 1000L))
        assertTrue(q.list().isEmpty())
    }

    @Test fun `an award with no achievement id is refused`() {
        val q = queue()
        assertFalse(q.write("r=awardachievement&u=player&t=abc&h=0", gameId = 42, atMs = 1000L))
        assertTrue(q.list().isEmpty())
    }

    @Test fun `a file nothing wrote is ignored rather than throwing`() {
        val q = queue()
        q.write(award(7), gameId = 42, atMs = 1000L)
        File(tmp.root, "Pending").also { it.mkdirs() }.let { File(it, "notes.txt").writeText("hello") }
        assertEquals(1, q.list().size)
    }

    @Test fun `an unlock that cannot use its temporary file is not left half written`() {
        val dir = File(tmp.root, "Pending").also { it.mkdirs() }
        val q = RaPendingUnlocks(dir, writerTag = "w")
        // A directory where the scratch file must go. A writer that wrote straight to the target
        // would instead leave whatever it managed before the process died.
        File(dir, "42-7.w.tmp").mkdirs()
        assertFalse(q.write(award(7), gameId = 42, atMs = 1000L))
        assertTrue(q.list().isEmpty())
        assertFalse(File(dir, "42-7.req").exists())
    }

    @Test fun `a scratch file a dead writer left behind is not read as a queued unlock`() {
        val dir = File(tmp.root, "Pending").also { it.mkdirs() }
        File(dir, "42-7.w.tmp").writeText("100")
        assertTrue(queue().list().isEmpty())
    }

    @Test fun `an empty queue reads as empty rather than missing`() {
        val q = queue()
        assertTrue(q.list().isEmpty())
        assertTrue(q.countByGame().isEmpty())
    }
}
