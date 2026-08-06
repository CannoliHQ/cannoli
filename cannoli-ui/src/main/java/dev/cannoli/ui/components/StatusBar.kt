package dev.cannoli.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliIconFont
import dev.cannoli.ui.theme.LocalScaleFactor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun cloudIcon(status: SaveSyncStatus): String? = when (status) {
    SaveSyncStatus.CHECKING, SaveSyncStatus.UPLOADING, SaveSyncStatus.DOWNLOADING -> CannoliIcons.CloudSync.glyph
    SaveSyncStatus.UP_TO_DATE -> CannoliIcons.CloudCheck.glyph
    SaveSyncStatus.CONFLICT -> CannoliIcons.CloudAlert.glyph
    SaveSyncStatus.ERROR -> CannoliIcons.AlertCircle.glyph
    SaveSyncStatus.OFFLINE -> CannoliIcons.CloudOff.glyph
    SaveSyncStatus.DISABLED -> null
}

// The RomM cache mirror, kept visually distinct from the cloud glyphs that mean save data.
private fun cacheSyncIcon(status: RommCacheSyncStatus): String? = when (status) {
    RommCacheSyncStatus.SYNCING -> CannoliIcons.DatabaseSync.glyph
    RommCacheSyncStatus.ERROR -> CannoliIcons.DatabaseAlert.glyph
    RommCacheSyncStatus.IDLE -> null
}

private fun batteryLevelIcon(percent: Int): String = when {
    percent >= 95 -> CannoliIcons.BatteryFull.glyph
    percent >= 85 -> CannoliIcons.Battery90.glyph
    percent >= 75 -> CannoliIcons.Battery80.glyph
    percent >= 65 -> CannoliIcons.Battery70.glyph
    percent >= 55 -> CannoliIcons.Battery60.glyph
    percent >= 45 -> CannoliIcons.Battery50.glyph
    percent >= 35 -> CannoliIcons.Battery40.glyph
    percent >= 25 -> CannoliIcons.Battery30.glyph
    percent >= 15 -> CannoliIcons.Battery20.glyph
    percent >= 5 -> CannoliIcons.Battery10.glyph
    else -> CannoliIcons.BatteryAlert.glyph
}

@Composable
fun StatusBar(
    updateAvailable: Boolean = false,
    kitchenRunning: Boolean = false,
    downloadCount: Int = 0,
    downloadsActive: Boolean = false,
    showWifi: Boolean = true,
    showBluetooth: Boolean = true,
    showVpn: Boolean = false,
    showClock: Boolean = true,
    showBattery: Boolean = true,
    batteryIconOnly: Boolean = false,
    showUpdate: Boolean = true,
    use24hTime: Boolean = false,
    textSizeSp: Int = 16,
    saveSyncStatus: SaveSyncStatus = SaveSyncStatus.DISABLED,
    rommCacheSyncStatus: RommCacheSyncStatus = RommCacheSyncStatus.IDLE,
) {
    val context = LocalContext.current
    val scaleFactor = LocalScaleFactor.current

    var batteryLevel by remember { mutableIntStateOf(0) }
    var isCharging by remember { mutableStateOf(false) }
    var wifiConnected by remember { mutableStateOf(false) }
    var hasVpn by remember { mutableStateOf(false) }
    var hasBluetooth by remember { mutableStateOf(false) }
    var rawTime by remember { mutableStateOf(Date()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            rawTime = Date()
        }
    }

    DisposableEffect(Unit) {
        rawTime = Date()

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryLevel = (level * 100) / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(batteryReceiver, batteryFilter)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        try {
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                fun updateNetState(caps: NetworkCapabilities?) {
                    wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                val net = cm.activeNetwork
                updateNetState(if (net != null) cm.getNetworkCapabilities(net) else null)
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        updateNetState(cm.getNetworkCapabilities(network))
                    }
                    override fun onLost(network: Network) {
                        updateNetState(null)
                    }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        updateNetState(caps)
                    }
                }
                cm.registerDefaultNetworkCallback(networkCallback!!)
            }
        } catch (_: SecurityException) {
            wifiConnected = false
        }

        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                hasBluetooth = state == BluetoothAdapter.STATE_ON
            }
        }
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            hasBluetooth = btAdapter?.isEnabled == true
            context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (_: SecurityException) {
            hasBluetooth = false
        }

        onDispose {
            try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            try { context.unregisterReceiver(btReceiver) } catch (_: Exception) {}
            try { networkCallback?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        }
    }

    val timeFormat = remember(use24hTime) {
        SimpleDateFormat(if (use24hTime) "HH:mm" else "h:mm a", Locale.getDefault())
    }
    val timeText = timeFormat.format(rawTime)
    val batteryPercent = stringResource(R.string.battery_level, batteryLevel)

    val colors = LocalCannoliColors.current
    val fontSize = (textSizeSp * scaleFactor).sp

    val iconStyle = TextStyle(
        fontFamily = LocalCannoliIconFont.current,
        fontWeight = FontWeight.Normal,
        fontSize = fontSize,
        color = colors.statusBar
    )

    val textStyle = TextStyle(
        fontFamily = LocalCannoliFont.current,
        fontWeight = FontWeight.Normal,
        fontSize = fontSize,
        color = colors.statusBar
    )

    val showUpdateIcon = updateAvailable && showUpdate
    val showBtIcon = showBluetooth && hasBluetooth
    val showWifiIcon = showWifi && wifiConnected
    val showVpnIcon = showVpn && hasVpn
    val anyVisible = cloudIcon(saveSyncStatus) != null || cacheSyncIcon(rommCacheSyncStatus) != null || kitchenRunning || downloadCount > 0 || downloadsActive || showUpdateIcon || showBtIcon || showWifiIcon || showVpnIcon || showBattery || showClock

    if (!anyVisible) return

    Row(
        modifier = Modifier.defaultMinSize(minHeight = (32 * scaleFactor).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((6 * scaleFactor).dp)
    ) {
        cloudIcon(saveSyncStatus)?.let { Text(text = it, style = iconStyle) }
        cacheSyncIcon(rommCacheSyncStatus)?.let { Text(text = it, style = iconStyle) }
        if (kitchenRunning) Text(text = CannoliIcons.Kitchen.glyph, style = iconStyle)
        if (downloadCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = CannoliIcons.Download.glyph, style = iconStyle)
                Spacer(modifier = Modifier.width((3 * scaleFactor).dp))
                Text(text = downloadCount.toString(), style = textStyle)
            }
        } else if (downloadsActive) {
            Text(text = CannoliIcons.Download.glyph, style = iconStyle)
        }
        if (showUpdateIcon) Text(text = CannoliIcons.Update.glyph, style = iconStyle)
        if (showBtIcon) Text(text = CannoliIcons.Bluetooth.glyph, style = iconStyle)
        if (showWifiIcon) Text(text = CannoliIcons.Wifi.glyph, style = iconStyle)
        if (showVpnIcon) Text(text = CannoliIcons.Vpn.glyph, style = iconStyle)
        if (showBattery) {
            if (isCharging) Text(text = CannoliIcons.Charging.glyph, style = iconStyle)
            if (batteryIconOnly) {
                Text(text = batteryLevelIcon(batteryLevel), style = iconStyle)
            } else {
                Text(text = batteryPercent, style = textStyle)
            }
        }
        if (showClock) Text(text = timeText, style = textStyle)
    }
}
