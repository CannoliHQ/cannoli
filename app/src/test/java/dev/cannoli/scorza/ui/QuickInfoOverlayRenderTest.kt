package dev.cannoli.scorza.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.cannoli.ui.components.InterfaceKind
import dev.cannoli.ui.components.NetworkEndpoint
import dev.cannoli.ui.components.QuickInfoOverlay
import dev.cannoli.ui.components.RommStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickInfoOverlayRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsOnlineRowsWithPinAndRomm() {
        compose.setContent {
            QuickInfoOverlay(
                endpoints = listOf(NetworkEndpoint(InterfaceKind.WIFI, "192.168.1.42", "192.168.1.42:1091")),
                kitchenRunning = true,
                pin = "4821",
                romm = RommStatus("romm.home.net", true),
                selectedIndex = 0,
            )
        }
        compose.onNodeWithText("192.168.1.42").assertIsDisplayed()
        compose.onNodeWithText("4821").assertIsDisplayed()
        compose.onNodeWithText("romm.home.net").assertIsDisplayed()
    }

    @Test fun showsOfflineEmptyStates() {
        compose.setContent {
            QuickInfoOverlay(endpoints = emptyList(), kitchenRunning = false, pin = null, romm = null, selectedIndex = 0)
        }
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Not running").assertIsDisplayed()
    }
}
