package dev.cannoli.scorza.netplay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.cannoli.scorza.util.RotatingLogFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Throwaway exercise of [WifiDirectGroup], run from the Debug menu.
 *
 * The group owner sends numbers and the other side prints them, which is the whole question netplay
 * asks of a transport: a socket to the host that stays up. It runs on RetroArch's own netplay port
 * so the answer is about the port netplay would use.
 *
 * Delete once the netplay design is settled. Nothing in the launcher depends on it.
 */
class WifiDirectProbe(context: Context) {

    private val log = RotatingLogFile("wifidirect.log", 256L * 1024, "HH:mm:ss.SSS")
    private val group = WifiDirectGroup(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private val numbers = ArrayDeque<Int>()
    private var link: Job? = null

    /** Fired on the main thread with what is visible now. */
    var onPeers: ((List<WifiDirectGroup.Peer>) -> Unit)? = null

    /** Fired on the main thread with a one-line state and the numbers seen so far. */
    var onLink: ((String, List<Int>) -> Unit)? = null

    /** True once this side has claimed group ownership, so the screen can offer the other action. */
    var hosting = false
        private set

    fun run(cannoliRoot: String, asHost: Boolean = false): String {
        log.init(cannoliRoot)
        group.onTrace = { log.write(it) }
        log.write("--- probe start on ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ---")

        if (!group.isSupported()) return "This device reports no Wi-Fi Direct support."
        group.missingPermission()?.let { return "Missing permission: ${it.substringAfterLast('.')}" }

        scope.launch {
            group.state.collect { state ->
                log.write("state=${state.javaClass.simpleName}")
                when (state) {
                    is WifiDirectGroup.State.Looking -> main.post { onPeers?.invoke(state.peers) }
                    is WifiDirectGroup.State.Grouped -> startLink(state.isOwner, state.owner)
                    is WifiDirectGroup.State.Failed -> report(state.reason)
                    else -> {}
                }
            }
        }

        hosting = asHost
        if (asHost) log.write("waiting to be joined; ownership is settled during connect")
        group.start(WifiDirectGroup.Advert(TXT))
        return "Wi-Fi Direct test running.\n\nLog: Logs/wifidirect.log"
    }

    fun connectTo(peer: WifiDirectGroup.Peer) {
        log.write("connect to ${peer.deviceName} (${peer.address})")
        report("connecting to ${peer.deviceName}")
        group.connect(peer)
    }

    fun stop() {
        hosting = false
        link?.cancel()
        link = null
        group.stop()
        synchronized(numbers) { numbers.clear() }
    }

    private fun startLink(isOwner: Boolean, owner: InetAddress) {
        if (link?.isActive == true) return
        link = scope.launch {
            try {
                if (isOwner) send() else receive(owner)
            } catch (e: Exception) {
                log.write("link ended ${e.javaClass.simpleName}: ${e.message}")
                report("link ended: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun send() {
        log.write("group owner, listening on $LINK_PORT")
        report("hosting, waiting for the other handheld")
        ServerSocket(LINK_PORT).use { server ->
            server.soTimeout = 60_000
            server.accept().use { socket ->
                log.write("client connected from ${socket.inetAddress?.hostAddress}")
                val out = DataOutputStream(socket.getOutputStream())
                val rng = java.util.Random()
                while (link?.isActive == true && !socket.isClosed) {
                    val n = rng.nextInt(9000) + 1000
                    out.writeInt(n)
                    out.flush()
                    log.write("sent $n")
                    add(n, "sending")
                    kotlinx.coroutines.delay(700)
                }
            }
        }
    }

    private fun receive(owner: InetAddress) {
        log.write("client, connecting to ${owner.hostAddress}:$LINK_PORT")
        report("connecting to the host")
        // The owner's socket is not listening the instant the group forms.
        Thread.sleep(1_500)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(owner, LINK_PORT), 10_000)
            log.write("connected to host")
            val input = DataInputStream(socket.getInputStream())
            while (link?.isActive == true && !socket.isClosed) {
                val n = input.readInt()
                log.write("received $n")
                add(n, "receiving")
            }
        }
    }

    private fun add(n: Int, state: String) {
        synchronized(numbers) {
            numbers.addFirst(n)
            while (numbers.size > 12) numbers.removeLast()
        }
        report(state)
    }

    private fun report(state: String) {
        val snapshot = synchronized(numbers) { numbers.toList() }
        main.post { onLink?.invoke(state, snapshot) }
    }

    private companion object {
        /** RetroArch's default netplay port, so this measures the port netplay would use. */
        const val LINK_PORT = 55435

        /** The record the design would carry, so the probe measures the real payload. */
        val TXT = mapOf(
            "nick" to "ProbeHost",
            "game" to "Super Mario Kart (USA)",
            "crc" to "8a1f2c4d",
            "core" to "snes9x",
            "port" to LINK_PORT.toString(),
        )
    }
}
