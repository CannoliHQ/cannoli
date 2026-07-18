package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameHubTargetTest {

    @Test fun `gamehub target round trips package app id and title`() {
        val target = GameHubTarget("gamehub.lite", "204630", "Retro City Rampage DX")

        assertEquals(target, GameHubTarget.decode(target.encode()))
    }

    @Test fun `ordinary package name is not a gamehub target`() {
        assertNull(GameHubTarget.decode("gamehub.lite"))
    }

    @Test fun `non numeric steam app id is rejected`() {
        assertNull(GameHubTarget.decode("cannoli-gamehub://gamehub.lite/not-an-id?title=Nope"))
    }

    @Test fun `featured games contain the requested GameHub library`() {
        assertEquals(
            listOf("Cave Story+", "Stardew Valley", "Nine Sols", "Retro City Rampage DX"),
            GameHubTarget.FEATURED_GAMES.map { it.title },
        )
    }
}
