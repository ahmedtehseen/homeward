package dev.personal.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val code: String, message: String, val retryable: Boolean) : IOException(message)
class Api(private val context: Context, val pairing: Pairing, private val network: Network?) {
    private val client: OkHttpClient
    init {
        require(pairing.fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid Mac certificate fingerprint" }
        val url = pairing.url.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "Enter an HTTPS server address without a path" }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client certificates are not used")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing server certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).hex()
                if (actual != pairing.fingerprint) throw java.security.cert.CertificateException("Mac identity changed; pair again")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            // Exact certificate pin above authenticates the server independently of DHCP/mDNS names.
            .hostnameVerifier { _, session -> runCatching { MessageDigest.getInstance("SHA-256").digest(session.peerCertificates[0].encoded).hex() == pairing.fingerprint }.getOrDefault(false) }
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .apply { if (network != null) { socketFactory(network.socketFactory); dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList() }) } }.build()
    }
    suspend fun request(method: String, path: String, json: JSONObject? = null, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap(), longRead: Boolean = false): JSONObject {
        val body = when { bytes != null -> bytes.toRequestBody("application/octet-stream".toMediaType()); method != "GET" -> (json?.toString() ?: "").toRequestBody("application/json".toMediaType()); else -> null }
        val req = Request.Builder().url(pairing.url.trimEnd('/') + path).method(method, body).apply {
            if (pairing.token.isNotEmpty()) header("Authorization", "Bearer ${pairing.token}")
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val call = (if (longRead) client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() else client).newCall(req)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val source = it.body?.source() ?: error("Empty server response")
                            source.request(64 * 1024 + 1L)
                            check(source.buffer.size <= 64 * 1024) { "Unexpectedly large server response" }
                            val result = JSONObject(source.readUtf8())
                            if (!it.isSuccessful) throw ApiFailure(result.optString("error"), result.optString("message", "Server error"), result.optBoolean("retryable", false))
                            val identity = it.header("X-Library-ID")
                            if (identity != pairing.library) throw ApiFailure("library_changed", "Connected library changed; pair again", false)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                }
            })
        }
    }
    suspend fun status() = request("GET", "/v1/status")
    suspend fun create(item: Media): JSONObject = request("POST", "/v1/uploads", JSONObject()
        .put("sha256", item.hash).put("size", item.size).put("name", item.name).put("mime", item.mime).put("taken", item.taken)
        .put("source_id", item.key).put("version", item.version).put("folder", item.folder))
    suspend fun upload(id: String) = request("GET", "/v1/uploads/$id")
    suspend fun chunk(id: String, offset: Long, bytes: ByteArray) = request("PUT", "/v1/uploads/$id/chunk", bytes = bytes,
        headers = mapOf("Upload-Offset" to offset.toString(), "Chunk-SHA256" to MessageDigest.getInstance("SHA-256").digest(bytes).hex()))
    suspend fun finish(id: String) = request("POST", "/v1/uploads/$id/finish")
    suspend fun verify(id: String) = request("POST", "/v1/backups/$id/verify", longRead = true)
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    companion object {
        fun wifi(context: Context): Network? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }
        suspend fun discover(context: Context, library: String): String? = withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                val nsd = context.getSystemService(NsdManager::class.java)
                lateinit var listener: NsdManager.DiscoveryListener
                var stopped = false
                val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
                fun stop() { if (!stopped) { stopped = true; runCatching { nsd.stopServiceDiscovery(listener) } } }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) = Unit
                    override fun onDiscoveryStopped(type: String) = Unit
                    override fun onStartDiscoveryFailed(type: String, code: Int) { if (continuation.isActive) continuation.resume(null) }
                    override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if ((!info.serviceName.startsWith("Homeward") && !info.serviceName.startsWith("Personal Backup")) || !resolving.compareAndSet(false, true)) return
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolving.set(false) }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                resolving.set(false)
                                if (info.attributes["library"]?.toString(Charsets.UTF_8) != library) return
                                @Suppress("DEPRECATION") val address = info.host?.hostAddress ?: return
                                val host = if (address.contains(':')) "[$address]" else address
                                stop(); if (continuation.isActive) continuation.resume("https://$host:${info.port}")
                            }
                        })
                    }
                }
                continuation.invokeOnCancellation { stop() }
                try { nsd.discoverServices("_pbackup._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
                catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }
}
