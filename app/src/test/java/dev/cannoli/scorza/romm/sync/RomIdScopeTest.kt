package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope RomM added so a device holding part of a library stops being answered for all of it.
 * Its edges are the whole point: null and empty mean opposite things, and 500 is a hard 422.
 */
class RomIdScopeTest {

    @Test fun `a scope carries the ids it was given, once each`() {
        assertEquals(listOf(3, 1, 2), romIdScope(listOf(3, 1, 2, 3, 1)))
    }

    /** Empty is an explicit empty scope to the server, which is not what "nothing to scope" means. */
    @Test fun `nothing to scope is null rather than an empty list`() {
        assertNull(romIdScope(emptyList()))
    }

    /** The server rejects a longer scope outright, so the request goes unscoped instead of failing. */
    @Test fun `a library past the cap is sent unscoped`() {
        assertEquals(MAX_ROM_IDS_PER_QUERY, romIdScope((1..MAX_ROM_IDS_PER_QUERY).toList())?.size)
        assertNull(romIdScope((1..MAX_ROM_IDS_PER_QUERY + 1).toList()))
    }

    /** Deduplication is what keeps a library of exactly the cap from tipping over it. */
    @Test fun `duplicates do not count against the cap`() {
        val atCap = (1..MAX_ROM_IDS_PER_QUERY).toList() + listOf(1, 2, 3)
        assertEquals(MAX_ROM_IDS_PER_QUERY, romIdScope(atCap)?.size)
    }

    /** Omitted, not null: an older server drops a field it does not know and answers as it does today. */
    @Test fun `an unscoped payload does not put rom_ids on the wire`() {
        val json = dev.cannoli.scorza.romm.rommJson.encodeToString(
            SyncNegotiatePayload.serializer(),
            SyncNegotiatePayload(deviceId = "dev-1", saves = emptyList()),
        )
        assertTrue(json, !json.contains("rom_ids"))
    }

    @Test fun `a scoped payload sends the ids`() {
        val json = dev.cannoli.scorza.romm.rommJson.encodeToString(
            SyncNegotiatePayload.serializer(),
            SyncNegotiatePayload(deviceId = "dev-1", saves = emptyList(), romIds = listOf(7, 9)),
        )
        assertTrue(json, json.contains("\"rom_ids\":[7,9]"))
    }
}
