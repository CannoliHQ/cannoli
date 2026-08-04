package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** NanoHTTPD's stock runner starts an unbounded thread per request and never reclaims one whose
 *  client has gone away, so abandoned work accumulates without limit. This caps how many requests
 *  run at once; the rest queue. Sized for concurrent file transfers, which legitimately hold a
 *  slot for the whole download, not just for the short JSON handlers. */
internal class KitchenAsyncRunner(
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val onExec: (NanoHTTPD.ClientHandler) -> Unit = {},
    private val onDone: () -> Unit = {},
) : NanoHTTPD.AsyncRunner {

    private val running = Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())

    private val pool = Executors.newFixedThreadPool(
        maxConcurrent,
        ThreadFactory { r -> Thread(r, "kitchen-request").apply { isDaemon = true } },
    )

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        running.add(clientHandler)
        pool.execute {
            onExec(clientHandler)
            try {
                clientHandler.run()
            } finally {
                onDone()
            }
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun closeAll() {
        synchronized(running) { running.toList() }.forEach { it.close() }
        pool.shutdownNow()
    }

    companion object {
        // Browsers open ~6 connections per host, and a large ROM download holds its slot for the
        // whole transfer, so this has to clear a few concurrent downloads plus a browsing client.
        const val DEFAULT_MAX_CONCURRENT = 16
    }
}
