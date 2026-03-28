package dev.cannoli.scorza.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.theme.MPlus1Code
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

private const val ICON_BLUETOOTH = "\uDB80\uDCAF"   // nf-md-bluetooth
private const val ICON_BLUETOOTH_OFF = "\uDB80\uDCB2" // nf-md-bluetooth_off
private const val ICON_WIFI = "\uDB81\uDDA9"         // nf-md-wifi
private const val ICON_WIFI_OFF = "\uDB81\uDDAA"     // nf-md-wifi_off
private const val ICON_VPN = "\uDB82\uDFC4"          // nf-md-shield_key
private const val ICON_KITCHEN = "\uDB81\uDC8B"      // 󰒋 nf-md-server

@Composable
fun StatusBar(
    use24hTime: Boolean = false,
    showWifi: Boolean = true,
    showBluetooth: Boolean = true,
    showVpn: Boolean = false,
    showClock: Boolean = true,
    showBattery: Boolean = true,
    showKitchen: Boolean = false,
    lineHeight: TextUnit = 32.sp,
    verticalPadding: Dp = 4.dp
) {
    val context = LocalContext.current

    var batteryLevel by remember { mutableIntStateOf(0) }
    var isCharging by remember { mutableStateOf(false) }
    var wifiConnected by remember { mutableStateOf(false) }
    var wifiEnabled by remember { mutableStateOf(true) }
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
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                wifiEnabled = wm?.isWifiEnabled == true
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        updateNetState(cm.getNetworkCapabilities(network))
                        wifiEnabled = wm?.isWifiEnabled == true
                    }
                    override fun onLost(network: Network) {
                        updateNetState(null)
                        wifiEnabled = wm?.isWifiEnabled == true
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

    val colors = LocalCannoliColors.current

    val iconStyle = TextStyle(
        fontFamily = MPlus1Code,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = colors.highlightText
    )

    val textStyle = TextStyle(
        fontFamily = MPlus1Code,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        color = colors.highlightText
    )

    val minHeight = with(LocalDensity.current) { lineHeight.toDp() } + verticalPadding * 2

    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(50))
            .background(colors.highlight)
            .padding(horizontal = pillInternalH, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showKitchen) {
            Text(text = ICON_KITCHEN, style = iconStyle)
        }

        if (showBluetooth) {
            Text(text = if (hasBluetooth) ICON_BLUETOOTH else ICON_BLUETOOTH_OFF, style = iconStyle)
        }

        if (showWifi) {
            val wifiIcon = when { wifiConnected -> ICON_WIFI; !wifiEnabled -> ICON_WIFI_OFF; else -> null }
            if (wifiIcon != null) Text(text = wifiIcon, style = iconStyle)
        }

        if (showVpn && hasVpn) {
            Text(text = ICON_VPN, style = iconStyle)
        }

        if (showBattery) {
            BatteryGauge(
                level = batteryLevel,
                isCharging = isCharging,
                textColor = colors.highlightText
            )
        }
        if (showClock) {
            Text(text = timeText, style = textStyle)
        }
    }
}

@Composable
private fun BatteryGauge(
    level: Int,
    isCharging: Boolean,
    textColor: Color
) {
    val bodyHeight = 14.dp
    val bodyWidth = 30.dp
    val tipWidth = 3.dp
    val tipHeight = 7.dp
    val borderWidth = 1.5.dp
    val cornerRadius = 3.dp
    val fillFraction = (level / 100f).coerceIn(0f, 1f)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(bodyWidth)
                .height(bodyHeight)
                .border(borderWidth, textColor, RoundedCornerShape(cornerRadius)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .fillMaxHeight()
                    .width((bodyWidth - 4.dp) * fillFraction)
                    .background(textColor, RoundedCornerShape(1.5.dp))
            )
            Text(
                text = stringResource(R.string.battery_level, level),
                style = TextStyle(
                    fontFamily = dev.cannoli.scorza.ui.theme.MPlus1Code,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    color = LocalCannoliColors.current.highlight
                ),
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Box(
            modifier = Modifier
                .width(tipWidth)
                .height(tipHeight)
                .padding(start = 0.5.dp)
                .background(
                    textColor,
                    RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
                )
        )
    }
}
