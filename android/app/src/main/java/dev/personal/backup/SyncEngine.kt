package dev.personal.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.digests.SHA256Digest
import org.json.JSONObject
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.random.Random

class Waiting(val reason: String) : Exception(reason)
class ChangedSource : Exception("Source changed during transfer")
data class ActiveTransfer(val name: String, val done: Long, val size: Long, val phase: String)
data class SyncView(val running: Boolean = false, val scanning: Boolean = false, val message: String = "Ready to back up", val freeBytes: Long = -1, val speed: Long = 0, val active: Map<String, ActiveTransfer> = emptyMap())
object SyncLive { val state = MutableStateFlow(SyncView()) }
class SyncEngine(private val context: Context) {
    private val settings = Settings(context)
    private val dao = BackupDatabase.get(context).media()
    private val library = Library(context)
    private var started = 0L
    private val acknowledged = java.util.concurrent.atomic.AtomicLong(0)
    private var deadline = Long.MAX_VALUE
    companion object { val gate = Mutex() }
    private suspend fun allowed() {
        currentCoroutineContext().ensureActive()
        if (SystemClock.elapsedRealtime() >= deadline) throw Waiting("Background batch complete; remaining work is saved")
        if (settings.paused) throw Waiting("Paused")
        if (Api.wifi(context) == null) throw Waiting("Waiting for Wi-Fi")
        if (android.os.Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) throw Waiting("Allow local network access")
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        if (!plugged && level * 100 / maxOf(scale, 1) < 20) throw Waiting("Waiting for battery above 20% or charging")
        if (settings.chargingOnly && !plugged) throw Waiting("Waiting for charging")
        if (context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) throw Waiting("Paused while the phone cools down")
    }
    private fun active(m: Media, phase: String, done: Long = m.offset) {
        SyncLive.state.update { it.copy(active = it.active + (m.key to ActiveTransfer(m.name, done, m.size, phase))) }
    }
    suspend fun run(manual: Boolean) = gate.withLock {
        var api: Api? = null
        started = SystemClock.elapsedRealtime(); acknowledged.set(0)
        deadline = if (manual) Long.MAX_VALUE else started + 8 * 60 * 1000
        SyncLive.state.update { it.copy(running = true, message = "Checking your library", speed = 0) }
        try {
            allowed()
            val p = settings.pairing() ?: throw Waiting("Pair with your Mac to begin")
            SyncLive.state.update { it.copy(scanning = true) }
            library.scan(checkpoint = { allowed() })
            SyncLive.state.update { it.copy(scanning = false) }
            if (!library.originalAccess()) throw Waiting("Allow original-media access before backup")
            val connected = connect(p)
            api = connected
            while (true) {
                allowed()
                val batch = dao.queue(System.currentTimeMillis())
                if (batch.isEmpty()) break
                coroutineScope { batch.map { item -> async(Dispatchers.IO) { transfer(item, connected) } }.awaitAll() }
            }
            val remaining = dao.remaining()
            SyncLive.state.update { it.copy(message = when {
                !library.fullAccess() -> "Limited library access · only permitted items were scanned"
                remaining > 0 -> "Retries scheduled · progress is saved"
                else -> "Backup queue complete · review any failed items"
            }) }
        } catch (e: Waiting) { SyncLive.state.update { it.copy(message = e.reason) } }
        catch (e: CancellationException) { SyncLive.state.update { it.copy(message = "Transfer interrupted · progress is saved") }; throw e }
        catch (e: SSLException) { SyncLive.state.update { it.copy(message = "Mac identity could not be verified; check pairing") } }
        catch (e: ApiFailure) { SyncLive.state.update { it.copy(message = e.message ?: "Mac needs attention") } }
        catch (e: IOException) { SyncLive.state.update { it.copy(message = "Mac unavailable · waiting to reconnect") } }
        catch (e: Exception) { SyncLive.state.update { it.copy(message = e.message ?: "Sync needs attention") } }
        finally { api?.close(); SyncLive.state.update { it.copy(running = false, scanning = false, active = emptyMap(), speed = 0) } }
    }
    private suspend fun connect(p: Pairing): Api {
        var api = Api(context, p, Api.wifi(context))
        try {
            val status = api.status()
            check(status.getString("library_id") == p.library) { "Library changed; pair again" }
            SyncLive.state.update { it.copy(freeBytes = status.getLong("free_bytes"), message = "Backing up to your Mac") }
            return api
        } catch (e: IOException) {
            api.close()
            if (e is ApiFailure || e is SSLException) throw e
            val url = Api.discover(context, p.library) ?: throw Waiting("Mac unavailable · check that it is awake and the server is running")
            val updated = p.copy(url = url)
            api = Api(context, updated, Api.wifi(context))
            try {
                val status = api.status()
                check(status.getString("library_id") == p.library) { "Library changed; pair again" }
                settings.savePairing(updated)
                SyncLive.state.update { it.copy(freeBytes = status.getLong("free_bytes"), message = "Backing up to your Mac") }
                return api
            } catch (e: Exception) { api.close(); throw e }
        } catch (e: Exception) { api.close(); throw e }
    }
    private suspend fun hash(source: Media): Media {
        var item = source.copy(state = "hashing")
        val initial = library.snapshot(item.uri) ?: throw IOException("Original no longer accessible")
        if (initial.version != item.version) throw ChangedSource()
        var count = item.hashOffset
        val digest = try {
            if (item.hashState.isNotEmpty() && count > 0) SHA256Digest(Base64.decode(item.hashState, Base64.NO_WRAP)) else SHA256Digest().also { count = 0 }
        } catch (_: Exception) { count = 0; SHA256Digest() }
        dao.put(item)
        library.openOriginal(item, count).use { input ->
            val buffer = ByteArray(256 * 1024)
            var checkpointAt = count
            var checkedAt = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - checkedAt > 1000) { allowed(); checkedAt = SystemClock.elapsedRealtime() }
                val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n); count += n
                if (count - checkpointAt >= 16 * 1024 * 1024) {
                    item = item.copy(hashOffset = count, hashState = Base64.encodeToString(digest.encodedState, Base64.NO_WRAP))
                    dao.put(item); active(item, "Hashing original", count); checkpointAt = count
                }
            }
        }
        if (count != item.size || library.snapshot(item.uri)?.version != item.version) throw ChangedSource()
        val result = ByteArray(32); digest.doFinal(result, 0)
        item = item.copy(hash = result.hex(), hashOffset = 0, hashState = "", state = "pending")
        dao.put(item); return item
    }
    private suspend fun transfer(source: Media, api: Api) {
        var item = source
        try {
            allowed()
            if (settings.excluded(item.folder)) { dao.put(item.copy(state = "excluded")); return }
            if (library.snapshot(item.uri)?.version != item.version) throw ChangedSource()
            if (item.hash.isEmpty()) item = hash(item)
            var remote = api.create(item)
            val serverState = remote.getString("state")
            item = item.copy(offset = remote.getLong("offset"), library = api.pairing.library, state = if (serverState == "committed") "backed_up" else serverState, error = "")
            dao.put(item)
            if (item.state == "backed_up") { ensureUnchanged(item); return }
            if (item.state == "uploading") {
                library.openOriginal(item, item.offset).use { input ->
                    while (item.offset < item.size) {
                        allowed(); ensureUnchanged(item)
                        val bytes = ByteArray(minOf(item.chunkBytes.toLong(), item.size - item.offset).toInt())
                        var filled = 0
                        while (filled < bytes.size) { currentCoroutineContext().ensureActive(); val n = input.read(bytes, filled, bytes.size - filled); if (n < 0) throw ChangedSource(); filled += n }
                        active(item, "Transferring")
                        val before = SystemClock.elapsedRealtime()
                        remote = api.chunk(item.hash, item.offset, bytes)
                        val offset = remote.getLong("offset")
                        check(offset == item.offset + bytes.size) { "Server offset differs from this transfer; retry to reconcile" }
                        item = item.copy(offset = offset, state = "uploading", error = "", nextAttempt = 0)
                        dao.put(item)
                        val sent = acknowledged.addAndGet(bytes.size.toLong())
                        SyncLive.state.update { it.copy(speed = sent * 1000 / maxOf(1, SystemClock.elapsedRealtime() - started)) }
                        active(item, "Transferring")
                        val limit = settings.bandwidthKiB
                        if (limit > 0) { val targetMs = bytes.size.toLong() * 2000 / (limit * 1024L); delay((targetMs - (SystemClock.elapsedRealtime() - before)).coerceAtLeast(0)) }
                    }
                }
                ensureUnchanged(item)
                remote = api.finish(item.hash)
                item = item.copy(state = "verifying"); dao.put(item)
            }
            while (remote.getString("state") == "verifying") {
                allowed(); active(item, "Mac is verifying", item.size); delay(1500)
                remote = api.upload(item.hash)
            }
            if (remote.getString("state") != "committed") throw ApiFailure("integrity_error", remote.optString("error", "Backup verification failed"), false)
            ensureUnchanged(item)
            dao.put(item.copy(state = "backed_up", offset = item.size, error = "", attempts = 0, nextAttempt = 0))
        } catch (e: ChangedSource) {
            val updated = library.snapshot(item.uri)
            if (updated != null) dao.put(updated.copy(state = "pending", error = "Source changed; backing up the current version"))
            else dao.put(item.copy(state = "failed", present = false, error = "Source no longer accessible"))
        } catch (e: Waiting) { throw e }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiFailure && e.code in setOf("storage_full", "drive_unavailable", "storage_error", "unauthorized", "library_changed")) throw Waiting(e.message ?: "Mac needs attention")
            if (e is SSLException) throw e
            if (Api.wifi(context) == null) throw Waiting("Waiting for Wi-Fi")
            if (e is java.net.ConnectException || e is java.net.UnknownHostException) throw Waiting("Mac unavailable · progress is saved")
            val attempts = item.attempts + 1
            val permanent = e is SecurityException || e is UnsupportedOperationException || (e is ApiFailure && !e.retryable)
            // Re-read to retain a newer hashing checkpoint written inside hash().
            val saved = dao.get(item.key) ?: item
            dao.put(saved.copy(state = if (permanent || attempts >= 8) "failed" else if (saved.hash.isEmpty()) "hashing" else "pending", attempts = attempts,
                error = e.message ?: "Transfer failed", nextAttempt = System.currentTimeMillis() + Rules.retryDelay(attempts, Random.nextDouble()), chunkBytes = if (attempts >= 2) 1024 * 1024 else saved.chunkBytes))
        } finally { SyncLive.state.update { it.copy(active = it.active - item.key) } }
    }
    private fun ensureUnchanged(item: Media) { if (library.snapshot(item.uri)?.version != item.version) throw ChangedSource() }
    suspend fun verifyCleanup(selected: List<Media>): List<Media> = gate.withLock {
        val p = settings.pairing() ?: error("Pair with your Mac first")
        check(Api.wifi(context) != null) { "Connect to Wi-Fi before cleanup" }
        val api = connect(p)
        try {
            selected.map { requested ->
                currentCoroutineContext().ensureActive()
                val saved = dao.get(requested.key) ?: error("Media record no longer exists")
                check(saved.state == "backed_up" && saved.library == p.library && saved.present) { "Item is no longer eligible for cleanup" }
                val current = library.snapshot(saved.uri) ?: error("Original is no longer available")
                check(current.version == saved.version) { "${saved.name} changed; back it up again" }
                SyncLive.state.update { it.copy(message = "Checking phone and Mac copies: ${saved.name}") }
                val hash = library.freshHash(current)
                val proof = api.verify(saved.hash)
                check(Rules.cleanupAllowed(hash, saved.hash, current.size, proof.getLong("size"), library.snapshot(saved.uri)?.version ?: "", saved.version,
                    p.library, proof.getString("library_id"), proof.optBoolean("verified") && proof.getString("sha256") == saved.hash)) { "Copies differ; cleanup denied for ${saved.name}" }
                saved
            }
        } finally { api.close() }
    }
}
