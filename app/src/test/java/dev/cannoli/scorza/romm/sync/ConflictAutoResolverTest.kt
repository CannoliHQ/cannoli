package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.cannoli.scorza.romm.sync.ConflictResolution.*

class ConflictAutoResolverTest {
    @Test fun `only server changed keeps server`() =
        assertEquals(KEEP_SERVER, ConflictAutoResolver.classify(localHash = "L", localAnchor = "L", serverHash = "S2", serverAnchor = "S"))

    @Test fun `only local changed keeps local`() =
        assertEquals(KEEP_LOCAL, ConflictAutoResolver.classify(localHash = "L2", localAnchor = "L", serverHash = "S", serverAnchor = "S"))

    @Test fun `both changed escalates`() =
        assertEquals(ESCALATE, ConflictAutoResolver.classify(localHash = "L2", localAnchor = "L", serverHash = "S2", serverAnchor = "S"))

    @Test fun `missing anchors escalate`() =
        assertEquals(ESCALATE, ConflictAutoResolver.classify(localHash = "L", localAnchor = null, serverHash = "S", serverAnchor = null))
}
