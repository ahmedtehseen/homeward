package dev.personal.backup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

class Library(private val context: Context) {
    private val resolver = context.contentResolver
    private val db = BackupDatabase.get(context)
    private val settings = Settings(context)
    private val columns = arrayOf("_id", "_display_name", "relative_path", "mime_type", "_size", "datetaken", "date_modified", "generation_modified")
    fun fullAccess() = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun originalAccess() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun read(c: Cursor, base: Uri, volumeVersion: String, seen: Long): Media {
        fun str(name: String) = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
        fun num(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        val id = num("_id")
        val uri = ContentUris.withAppendedId(base, id)
        return Media(key = uri.toString(), uri = uri.toString(), name = str("_display_name"), folder = str("relative_path"), mime = str("mime_type"),
            size = num("_size"), taken = num("datetaken"), version = "$volumeVersion:${num("generation_modified")}:${num("date_modified")}:${num("_size")}", seen = seen)
    }
    suspend fun scan(force: Boolean = false, checkpoint: suspend () -> Unit = {}) {
        val scanTime = System.currentTimeMillis()
        val full = force || scanTime - settings.lastFullScan > 24 * 60 * 60 * 1000
        var complete = fullAccess()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            currentCoroutineContext().ensureActive(); checkpoint()
            val version = MediaStore.getVersion(context, volume)
            val generation = MediaStore.getGeneration(context, volume)
            val previous = settings.checkpoint(volume).split('|')
            val useIncremental = !full && previous.size == 2 && previous[0] == version
            val oldGeneration = if (useIncremental) previous[1].toLongOrNull() ?: 0 else 0
            for (base in listOf(MediaStore.Images.Media.getContentUri(volume), MediaStore.Video.Media.getContentUri(volume))) {
                var last = -1L
                while (true) {
                    currentCoroutineContext().ensureActive(); checkpoint()
                    val selection = "_id > ? AND is_pending=0 AND is_trashed=0" + if (useIncremental) " AND generation_modified > ?" else ""
                    val args = if (useIncremental) arrayOf(last.toString(), oldGeneration.toString()) else arrayOf(last.toString())
                    val query = Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 250)
                    }
                    val fresh = mutableListOf<Media>()
                    try {
                        val cursor = resolver.query(base, columns, query, null) ?: error("Photo library is unavailable")
                        cursor.use { while (it.moveToNext()) { last = it.getLong(0); fresh += read(it, base, version, scanTime) } }
                    } catch (_: SecurityException) { complete = false; break }
                    if (fresh.isEmpty()) break
                    db.withTransaction {
                        val updates = fresh.map { item ->
                            val old = db.media().get(item.key)
                            val excluded = settings.excluded(item.folder)
                            when {
                                excluded -> item.copy(state = "excluded")
                                old?.state == "dismissed" -> item
                                old != null && old.version == item.version && old.state != "excluded" -> old.copy(present = true, seen = scanTime)
                                item.size <= 0 -> item.copy(state = "failed", error = "Empty or unfinished media file")
                                else -> item
                            }
                        }
                        db.media().put(updates)
                    }
                    if (fresh.size < 250) break
                }
            }
            if (complete && MediaStore.getVersion(context, volume) == version) {
                settings.checkpoint(volume, "$version|$generation")
                if (!useIncremental) db.media().reconcile(scanTime, "content://media/$volume/%")
            }
        }
        if (full && complete) settings.lastFullScan = scanTime
    }
    fun snapshot(uriText: String): Media? {
        val uri = Uri.parse(uriText)
        val volume = MediaStore.getVolumeName(uri)
        val base = Uri.parse(uriText.substringBeforeLast('/'))
        return resolver.query(uri, columns, "is_pending=0 AND is_trashed=0", null, null)?.use {
            if (it.moveToFirst()) read(it, base, MediaStore.getVersion(context, volume), System.currentTimeMillis()) else null
        }
    }
    fun openOriginal(item: Media, offset: Long = 0): InputStream {
        check(originalAccess()) { "Grant original-media access to preserve embedded metadata" }
        val uri = MediaStore.setRequireOriginal(Uri.parse(item.uri))
        val afd = resolver.openTypedAssetFileDescriptor(uri, "*/*", Bundle().apply {
            putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
        }) ?: error("Original media is unavailable")
        val input = afd.createInputStream()
        try {
            if (offset > 0) {
                try { input.channel.position(afd.startOffset + offset) }
                catch (_: Exception) {
                    var remaining = offset
                    val discard = ByteArray(256 * 1024)
                    while (remaining > 0) {
                        val n = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                        if (n < 0) error("Original is shorter than expected")
                        remaining -= n
                    }
                }
            }
            return input
        } catch (e: Exception) { input.close(); throw e }
    }
    suspend fun freshHash(item: Media, checkpoint: suspend () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        openOriginal(item).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive(); checkpoint()
                val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n); count += n
            }
        }
        check(count == item.size) { "Original file length changed" }
        return digest.digest().hex()
    }
}
fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
