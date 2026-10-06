package dev.cannoli.scorza.netplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class WifiDirectGroup(private val context: Context) {

    data class Peer(
        val deviceName: String,
        val address: String,
        val record: Map<String, String>,
    )

    data class Advert(val record: Map<String, String>)

    sealed interface State {
        data object Idle : State
        data class Looking(val peers: List<Peer>) : State
        data class Joining(val peer: Peer) : State
        data class Grouped(val isOwner: Boolean, val owner: InetAddress) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    var onTrace: ((String) -> Unit)? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val peers = LinkedHashMap<String, Peer>()
    private var started = false
    private var discoveryStartedAt = 0L
    private var firstPeerReported = false
    private var discoveryRunning = false

    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            context.getSystemService(Context.WIFI_P2P_SERVICE) != null

    fun missingPermission(): String? {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.NEARBY_WIFI_DEVICES"
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.takeIf { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    fun start(advert: Advert) {
        if (started) stop()
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            fail("Wi-Fi Direct unavailable")
            return
        }
        val ch = runCatching { mgr.initialize(context, Looper.getMainLooper(), null) }.getOrNull()
        if (ch == null) {
            fail("could not open a Wi-Fi Direct channel")
            return
        }
        manager = mgr
        channel = ch
        started = true
        peers.clear()
        _state.value = State.Looking(emptyList())
        registerReceiver()

        act("removeGroup") { mgr.removeGroup(ch, it) }
        act("clearLocalServices") { mgr.clearLocalServices(ch, it) }
        act("clearServiceRequests") { mgr.clearServiceRequests(ch, it) }

        val info = WifiP2pDnsSdServiceInfo.newInstance(INSTANCE, SERVICE_TYPE, advert.record)
        act("addLocalService") { mgr.addLocalService(ch, info, it) }

        mgr.setDnsSdResponseListeners(
            ch,
            { _, _, _ -> },
            { _, record, device ->
                val peer = Peer(
                    deviceName = device.deviceName.orEmpty().ifEmpty { device.deviceAddress.orEmpty() },
                    address = device.deviceAddress.orEmpty(),
                    record = record.orEmpty().mapValues { (_, v) -> v.orEmpty() },
                )
                if (!firstPeerReported) {
                    firstPeerReported = true
                    trace("first peer after ${android.os.SystemClock.elapsedRealtime() - discoveryStartedAt}ms")
                }
                if (peers.put(peer.address, peer) != peer) publishPeers()
            },
        )
        act("addServiceRequest") { mgr.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), it) }
        discoveryStartedAt = android.os.SystemClock.elapsedRealtime()
        firstPeerReported = false
        act("discoverServices") { mgr.discoverServices(ch, it) }

    }

    fun connect(peer: Peer) {
        val mgr = manager ?: return
        val ch = channel ?: return
        _state.value = State.Joining(peer)
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            groupOwnerIntent = 0
        }
        act("connect") { mgr.connect(ch, config, it) }
    }

    fun stop() {
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            act("stopPeerDiscovery") { mgr.stopPeerDiscovery(ch, it) }
            act("clearLocalServices") { mgr.clearLocalServices(ch, it) }
            act("clearServiceRequests") { mgr.clearServiceRequests(ch, it) }
            act("removeGroup") { mgr.removeGroup(ch, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { ch.close() }
        }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        manager = null
        channel = null
        started = false
        discoveryRunning = false
        discoveryStartedAt = 0L
        peers.clear()
        _state.value = State.Idle
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        trace("p2p enabled=$on")
                        if (started && !on) {
                            fail("Wi-Fi Direct switched off")
                        } else if (started && on && _state.value is State.Failed) {
                            trace("p2p back, restarting")
                            restartDiscovery()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> readConnection()
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        if (started) discoveryRunning = true else onDiscoveryStopped()
                    }
                }
            }
        }
        receiver = r
        context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun onDiscoveryStopped() {
        if (!started) return
        if (_state.value !is State.Looking) return
        // Only a discovery seen to start can have stopped. Registering the receiver delivers the
        // current state first, and that arrives as a stop for something never begun; a timestamp
        // cannot tell the two apart, because start() has already set it by the time the broadcast
        // is delivered to the main thread.
        if (!discoveryRunning) return
        discoveryRunning = false
        val mgr = manager ?: return
        val ch = channel ?: return
        trace("discovery stopped, re-arming")
        discoveryStartedAt = android.os.SystemClock.elapsedRealtime()
        act("discoverServices") { mgr.discoverServices(ch, it) }
    }

    private fun readConnection() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.requestConnectionInfo(ch) { info ->
                trace("group formed=${info.groupFormed} owner=${info.isGroupOwner} addr=${info.groupOwnerAddress?.hostAddress}")
                val owner = info.groupOwnerAddress
                if (info.groupFormed && owner != null) {
                    _state.value = State.Grouped(info.isGroupOwner, owner)
                } else if (_state.value is State.Grouped) {
                    _state.value = State.Looking(peers.values.toList())
                }
            }
        }
    }

    private fun restartDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        _state.value = State.Looking(peers.values.toList())
        act("addServiceRequest") { mgr.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), it) }
        act("discoverServices") { mgr.discoverServices(ch, it) }
    }

    private fun publishPeers() {
        if (_state.value is State.Grouped) return
        _state.value = State.Looking(peers.values.toList())
    }

    private fun fail(reason: String) {
        trace("failed: $reason")
        _state.value = State.Failed(reason)
    }

    private fun act(what: String, call: (WifiP2pManager.ActionListener) -> Unit) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { trace("$what ok") }
            override fun onFailure(reason: Int) { trace("$what failed ${reasonName(reason)}") }
        }
        runCatching { call(listener) }.onFailure { trace("$what threw ${it.javaClass.simpleName}") }
    }

    private fun reasonName(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.ERROR -> "ERROR"
        else -> "reason=$reason"
    }

    private fun trace(message: String) = onTrace?.invoke(message)

    private companion object {
        const val INSTANCE = "cannoli"
        const val SERVICE_TYPE = "_cannoli._tcp"
    }
}
