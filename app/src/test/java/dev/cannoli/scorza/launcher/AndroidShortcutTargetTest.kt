package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidShortcutTargetTest {

    @Test fun `shortcut target round trips package and id`() {
        val target = AndroidShortcutTarget("gamehub.lite", "Cave Story+")

        assertEquals(target, AndroidShortcutTarget.decode(target.encode()))
    }

    @Test fun `ordinary package name is not a shortcut target`() {
        assertNull(AndroidShortcutTarget.decode("gamehub.lite"))
    }

    @Test fun `malformed shortcut target is rejected`() {
        assertNull(AndroidShortcutTarget.decode("cannoli-shortcut://gamehub.lite"))
    }
}
