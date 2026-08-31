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

/**
 * A Wi-Fi Direct group, from advertising to teardown.
 *
 * Two handhelds in the same room with no network between them: each advertises what it is playing,
 * sees what the other is playing without joining anything, and one of them forms a group. The
 * group is a real interface with its own subnet, so anything above this can just open a socket.
 *
 * Depends on nothing but [Context] on purpose. Hosting is driven from the in-game menu, which runs
 * in the :retroarch process, and the channel belongs to whichever process opens it, so this has to
 * be able to live there rather than in the launcher.
 */
class WifiDirectGroup(private val context: Context) {

    /** What another handheld is advertising. [address] is the only part connect() can use. */
    data class Peer(
        val deviceName: String,
        val address: String,
        val record: Map<String, String>,
    )

    /** What this handheld advertises. Kept as a map because the record is the wire format. */
    data class Advert(val record: Map<String, String>)

    sealed interface State {
        /** Nothing claimed: no channel, no advertisement, no group. */
        data object Idle : State
        /** Advertising and listening. [peers] grows as other handhelds answer. */
        data class Looking(val peers: List<Peer>) : State
        data class Joining(val peer: Peer) : State
        /** A group exists. [owner] is the address to connect a socket to, whichever side you are. */
        data class Grouped(val isOwner: Boolean, val owner: InetAddress) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Optional trace, so a caller can log without this owning a log file. */
    var onTrace: ((String) -> Unit)? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val peers = LinkedHashMap<String, Peer>()
    private var started = false
    private var discoveryStartedAt = 0L
    private var firstPeerReported = false
    private var discoveryRunning = false

    /** Whether this device can do any of it, before anything is claimed. */
    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            context.getSystemService(Context.WIFI_P2P_SERVICE) != null

    /** The permission discovery needs, which changed name at API 33. Null when it is already held. */
    fun missingPermission(): String? {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.NEARBY_WIFI_DEVICES"
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.takeIf { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Claims the radio: advertise [advert] and listen for others.
     *
     * Any group left behind is removed first. A channel dying without a teardown leaves its group
     * up, and the next attempt then joins nothing and reports no error, which is the hardest
     * version of this to diagnose.
     */
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

        // BUSY here is the normal answer when there is no group to remove, and is not a problem.
        // It is issued anyway because a channel that died without a teardown leaves its group up.
        act("removeGroup") { mgr.removeGroup(ch, it) }
        // Cleared before advertising, or a second start leaves the first advertisement up and one
        // handheld arrives twice on the other's list.
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
                // Keyed by device: one handheld answers repeatedly within a pass, and once per
                // service it still advertises.
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

    /**
     * Joins [peer], asking to be the client rather than the owner.
     *
     * The netplay server binds on the host and the client dials the group owner's address, so the
     * host has to win ownership. groupOwnerIntent is how that is asked for without forming a group
     * in advance: 0 says this side does not want to own it, which leaves the other side to. It is
     * a bias in the negotiation rather than a guarantee, so callers still read isOwner afterwards
     * rather than assuming.
     */
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

    /**
     * Gives the radio back.
     *
     * Every step is attempted even if an earlier one fails: a half-released channel is what makes
     * the next start come back BUSY, and there is nothing useful to do with an error here anyway.
     */
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
                            // It came back, most likely from the idle shutdown above.
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

    /**
     * Discovery is not a subscription. Android runs it for two minutes and stops, and then shuts
     * the whole P2P stack down as idle, so a list left open longer than that goes quietly deaf and
     * a retry lands on a disabled stack. Re-arming is what makes an open list keep meaning
     * something; nothing else restarts it.
     */
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
                    // The group went away under us; back to looking rather than pretending.
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
