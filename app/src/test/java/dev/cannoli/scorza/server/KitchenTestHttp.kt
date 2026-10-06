package dev.cannoli.scorza.server

import java.net.HttpURLConnection
import java.net.URL

private const val TIMEOUT_MS = 5_000

internal fun openKitchenConnection(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }
