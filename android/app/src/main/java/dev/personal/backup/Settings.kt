package dev.personal.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Pairing(val url: String, val fingerprint: String, val library: String, val token: String) {
    fun json() = JSONObject().put("url", url).put("fingerprint", fingerprint).put("library_id", library).put("token", token)
    companion object { fun from(j: JSONObject) = Pairing(j.getString("url"), j.getString("fingerprint"), j.getString("library_id"), j.optString("token")) }
}
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var paused: Boolean get() = prefs.getBoolean("paused", false); set(v) { prefs.edit().putBoolean("paused", v).apply() }
    var chargingOnly: Boolean get() = prefs.getBoolean("charging", false); set(v) { prefs.edit().putBoolean("charging", v).apply() }
    var bandwidthKiB: Int get() = prefs.getInt("bandwidth", 0); set(v) { prefs.edit().putInt("bandwidth", v.coerceIn(0, 102400)).apply() }
    var exclusions: String get() = prefs.getString("exclusions", "")!!; set(v) { prefs.edit().putString("exclusions", v).apply(); forceScan() }
    var lastFullScan: Long get() = prefs.getLong("fullScan", 0); set(v) { prefs.edit().putLong("fullScan", v).apply() }
    fun forceScan() { lastFullScan = 0 }
    fun checkpoint(volume: String) = prefs.getString("scan:$volume", "")!!
    fun checkpoint(volume: String, value: String) { prefs.edit().putString("scan:$volume", value).apply() }
    fun excluded(folder: String) = exclusions.lines().map { it.trim().trim('/') }.filter { it.isNotEmpty() }.any { folder.trim('/').equals(it, true) || folder.trim('/').startsWith("$it/", true) }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("personal-backup", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("personal-backup", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun pairing(): Pairing? {
        val stored = prefs.getString("pairing", null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            Pairing.from(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)))
        } catch (_: Exception) { null }
    }
    fun savePairing(p: Pairing) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(p.json().toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("pairing", Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }
}
object Rules {
    fun retryDelay(attempt: Int, jitter: Double): Long = ((2_000L shl attempt.coerceIn(0, 9)).coerceAtMost(900_000L) * (0.75 + jitter.coerceIn(0.0, 1.0) * 0.25)).toLong()
    fun cleanupAllowed(currentHash: String, backedHash: String, currentSize: Long, backedSize: Long, currentVersion: String, backedVersion: String, expectedLibrary: String, returnedLibrary: String, verified: Boolean) =
        verified && currentHash.isNotBlank() && currentHash == backedHash && currentSize == backedSize && currentVersion == backedVersion && expectedLibrary.isNotBlank() && expectedLibrary == returnedLibrary
}
