package dev.cannoli.scorza.settings

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.igm.ShortcutTable
import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlobalOverridesManagerShortcutsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manager(): GlobalOverridesManager {
        CannoliPaths(tmp.root.absolutePath).shortcutsIni.parentFile?.mkdirs()
        return GlobalOverridesManager { tmp.root.absolutePath }
    }

    @Test fun `the held menu round-trips through shortcuts ini`() {
        val m = manager()
        m.saveShortcuts(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(108)))
        assertEquals(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(108)), m.readShortcuts())
    }

    // What the launcher reads is what the game is handed, and native is handed the encoded table.
    @Test fun `the held menu reaches native with its hold and its pass through`() {
        val m = manager()
        m.saveShortcuts(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(108)))
        assertEquals(
            listOf(ShortcutAction.OPEN_MENU_HOLD.ordinal, ShortcutAction.OPEN_MENU_HOLD.holdMs, 1, 1, 108),
            ShortcutTable.encode(m.readShortcuts()).toList(),
        )
    }
}
