package dev.cannoli.scorza.server

import java.net.HttpURLConnection
import java.net.URL

// Without these a server that accepts and never answers blocks the socket read forever, which
// stalls the whole suite instead of failing one test with a stack trace.
private const val TIMEOUT_MS = 5_000

internal fun openKitchenConnection(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }
