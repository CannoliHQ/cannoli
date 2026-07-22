package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class RommHttp(
    private val tokenProvider: () -> String?,
    private val allowSelfSignedProvider: () -> Boolean,
    private val hostProvider: () -> String = { "" },
) {
    private var cached: OkHttpClient? = null
    private var builtWithSelfSigned: Boolean = false
    private var cachedDownload: OkHttpClient? = null
    private var downloadBase: OkHttpClient? = null

    @Synchronized
    fun client(): OkHttpClient {
        val selfSigned = allowSelfSignedProvider()
        val current = cached
        if (current != null && selfSigned == builtWithSelfSigned) return current
        val built = build(selfSigned)
        cached = built
        builtWithSelfSigned = selfSigned
        return built
    }

    // Streaming ROM/firmware/manual downloads reuse the base client's pool and auth but need a much
    // longer read timeout: the server may spend minutes zipping a large ROM before its first byte.
    @Synchronized
    fun downloadClient(): OkHttpClient {
        val base = client()
        val existing = cachedDownload
        if (existing != null && downloadBase === base) return existing
        val built = base.newBuilder()
            .readTimeout(DOWNLOAD_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
        cachedDownload = built
        downloadBase = base
        return built
    }

    // Drop pooled sockets whose network has gone away. Without this the next request can pick a dead
    // connection and block until its timeout rather than failing fast onto a fresh one.
    @Synchronized
    fun evictConnections() {
        cached?.connectionPool?.evictAll()
    }

    private fun build(allowSelfSigned: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = tokenProvider()
                val request = if (token.isNullOrEmpty() || !isRommHost(chain.request().url.host)) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
        if (allowSelfSigned) applyTrustAll(builder)
        return builder.build()
    }

    private fun isRommHost(requestHost: String): Boolean {
        val configured = hostProvider()
        if (configured.isBlank()) return true
        val rommHost = if (configured.contains("://")) {
            configured.toHttpUrlOrNull()?.host
        } else {
            "https://$configured".toHttpUrlOrNull()?.host
        } ?: return true
        return requestHost.equals(rommHost, ignoreCase = true)
    }

    private fun applyTrustAll(builder: OkHttpClient.Builder) {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier { _, _ -> true }
    }

    companion object {
        private const val DOWNLOAD_READ_TIMEOUT_MINUTES = 10L
    }
}
