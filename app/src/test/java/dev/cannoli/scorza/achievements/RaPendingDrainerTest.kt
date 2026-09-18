package dev.cannoli.scorza.achievements

import dev.cannoli.core.achievements.RaPendingUnlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RaPendingDrainerTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeClient(private val replies: MutableList<RaConnectClient.RawResponse>) :
        RaConnectClient() {
        val sent = mutableListOf<String>()
        override fun replay(postData: String): RawResponse {
            sent += postData
            return replies.removeAt(0)
        }
    }

    private fun queue(): RaPendingUnlocks = RaPendingUnlocks(File(tmp.root, "Pending")).also {
        it.write("r=awardachievement&u=p&t=a&a=7&h=0", gameId = 42, atMs = 1000L)
        it.write("r=awardachievement&u=p&t=a&a=8&h=0", gameId = 42, atMs = 2000L)
    }

    @Test fun `every unlock the server accepts is submitted and deleted`() = runBlocking {
        val q = queue()
        val client = FakeClient(mutableListOf(
            RaConnectClient.RawResponse(200, """{"Success":true}"""),
            RaConnectClient.RawResponse(200, """{"Success":true}"""),
        ))
        val result = RaPendingDrainer(q, client).drain()
        assertEquals(2, result.submitted)
        assertEquals(0, result.left)
        assertTrue(q.list().isEmpty())
        assertEquals(listOf(7, 8), client.sent.map { it.substringAfter("&a=").substringBefore("&").toInt() })
    }

    @Test fun `an unlock the server already has is done rather than stuck`() = runBlocking {
        val q = queue()
        val client = FakeClient(mutableListOf(
            RaConnectClient.RawResponse(200, """{"Success":false,"Error":"User already has this achievement awarded."}"""),
            RaConnectClient.RawResponse(200, """{"Success":true}"""),
        ))
        val result = RaPendingDrainer(q, client).drain()
        assertEquals(2, result.submitted)
        assertTrue(q.list().isEmpty())
    }

    @Test fun `an unreachable server keeps everything and stops trying`() = runBlocking {
        val q = queue()
        val client = FakeClient(mutableListOf(RaConnectClient.RawResponse(-1, "")))
        val result = RaPendingDrainer(q, client).drain()
        assertEquals(0, result.submitted)
        assertEquals(2, result.left)
        // What the OSD reads to say the server was never asked rather than that it took none.
        assertFalse(result.reached)
        assertEquals(1, client.sent.size)
        assertEquals(2, q.list().size)
    }

    @Test fun `a refusal that is not a duplicate keeps the unlock for another day`() = runBlocking {
        val q = queue()
        val client = FakeClient(mutableListOf(
            RaConnectClient.RawResponse(200, """{"Success":false,"Error":"Invalid token"}"""),
            RaConnectClient.RawResponse(200, """{"Success":true}"""),
        ))
        val result = RaPendingDrainer(q, client).drain()
        assertEquals(1, result.submitted)
        assertEquals(1, result.left)
        // The server answered and refused one, which is not the same as being unreachable.
        assertTrue(result.reached)
        assertEquals(listOf(7), q.list().map { it.achievementId })
    }

    @Test fun `an empty queue reaches nobody and reports no failure`() = runBlocking {
        val q = RaPendingUnlocks(File(tmp.root, "Pending"))
        val client = FakeClient(mutableListOf())
        val result = RaPendingDrainer(q, client).drain()
        assertEquals(0, result.submitted)
        assertEquals(0, result.left)
        assertTrue(result.reached)
        assertTrue(client.sent.isEmpty())
    }

    @Test fun `refreshed reports only the game whose queue emptied`() = runBlocking {
        val q = RaPendingUnlocks(File(tmp.root, "Pending"))
        q.write("r=awardachievement&u=p&t=a&a=7&h=0", gameId = 42, atMs = 1000L)
        q.write("r=awardachievement&u=p&t=a&a=9&h=0", gameId = 99, atMs = 2000L)
        val client = FakeClient(mutableListOf(
            RaConnectClient.RawResponse(200, """{"Success":true}"""),
            RaConnectClient.RawResponse(200, """{"Success":false,"Error":"Invalid token"}"""),
        ))
        val result = RaPendingDrainer(q, client).drain()
        assertEquals(setOf(42), result.refreshed)
        assertEquals(listOf(99), q.list().map { it.gameId })
    }
}
