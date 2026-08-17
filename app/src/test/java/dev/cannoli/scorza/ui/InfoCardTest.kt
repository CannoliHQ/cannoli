package dev.cannoli.scorza.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.cannoli.ui.components.InfoCard
import dev.cannoli.ui.components.InfoRowItem
import dev.cannoli.ui.components.InfoStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InfoCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersLabelUppercasedAndValue() {
        compose.setContent {
            InfoCard(items = listOf(InfoRowItem("Interface", "Wi-Fi")))
        }
        compose.onNodeWithText("INTERFACE").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi").assertIsDisplayed()
    }

    @Test fun rendersMutedValueAndStatusRow() {
        compose.setContent {
            InfoCard(items = listOf(
                InfoRowItem("IP Address", "Not connected", muted = true),
                InfoRowItem("RomM", "romm.home.net", status = InfoStatus.OK),
            ))
        }
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("romm.home.net").assertIsDisplayed()
    }
}
