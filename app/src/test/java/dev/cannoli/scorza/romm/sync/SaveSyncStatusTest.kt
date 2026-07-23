package dev.cannoli.scorza.romm.sync

import dev.cannoli.ui.components.SaveSyncStatus.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveSyncStatusTest {
    @Test fun `disabled wins`() = assertEquals(DISABLED, resolveIdleStatus(enabled = false, online = true, pendingConflicts = 0, hadError = false))
    @Test fun `offline when enabled but no network`() = assertEquals(OFFLINE, resolveIdleStatus(true, online = false, 0, false))
    @Test fun `conflict when conflicts pending`() = assertEquals(CONFLICT, resolveIdleStatus(true, true, pendingConflicts = 2, hadError = false))
    @Test fun `error when last sync errored`() = assertEquals(ERROR, resolveIdleStatus(true, true, 0, hadError = true))
    @Test fun `conflict wins over error`() = assertEquals(CONFLICT, resolveIdleStatus(true, true, pendingConflicts = 1, hadError = true))
    @Test fun `up to date otherwise`() = assertEquals(UP_TO_DATE, resolveIdleStatus(true, true, 0, false))
}
