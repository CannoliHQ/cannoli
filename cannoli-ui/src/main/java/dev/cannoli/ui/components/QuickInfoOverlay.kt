package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun QuickInfoOverlay(
    endpoints: List<NetworkEndpoint>,
    kitchenRunning: Boolean,
    pin: String?,
    romm: RommStatus?,
    selectedIndex: Int,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val colors = LocalCannoliColors.current
    val labels = NetworkInfoLabels(
        interfaceLabel = stringResource(R.string.quick_info_interface),
        ip = stringResource(R.string.quick_info_ip),
        kitchen = stringResource(R.string.quick_info_kitchen),
        pin = stringResource(R.string.quick_info_pin),
        romm = stringResource(R.string.quick_info_romm),
        wifi = stringResource(R.string.quick_info_iface_wifi),
        ethernet = stringResource(R.string.quick_info_iface_ethernet),
        vpn = stringResource(R.string.quick_info_iface_vpn),
        other = stringResource(R.string.quick_info_iface_other),
        notConnected = stringResource(R.string.quick_info_not_connected),
        notRunning = stringResource(R.string.quick_info_not_running),
        unreachable = stringResource(R.string.quick_info_unreachable),
        dash = stringResource(R.string.quick_info_value_none),
    )
    val rows = NetworkInfoRows.build(endpoints, kitchenRunning, pin, romm, selectedIndex, labels)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(screenInsets()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.quick_info_title),
                style = LocalCannoliTypography.current.titleLarge.copy(color = colors.text),
            )
            Spacer(modifier = Modifier.height(Spacing.Lg))
            InfoCard(
                items = rows,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(0.82f),
            )
            Spacer(modifier = Modifier.height(footerReservation()))
        }
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(screenPadding),
            leftItems = buildList {
                add(buttonStyle.back to stringResource(R.string.label_back))
                if (endpoints.size > 1) add(DPAD_HORIZONTAL to stringResource(R.string.label_interface))
            },
            rightItems = emptyList(),
        )
    }
}
