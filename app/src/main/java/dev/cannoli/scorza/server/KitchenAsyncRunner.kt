package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

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
        const val DEFAULT_MAX_CONCURRENT = 16
    }
}
