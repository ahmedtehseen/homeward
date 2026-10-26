package dev.personal.backup

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media", indices = [Index("state"), Index("nextAttempt"), Index("seen"), Index("folder")])
data class Media(
    @PrimaryKey val key: String,
    val uri: String, val name: String, val folder: String, val mime: String,
    val size: Long, val taken: Long, val version: String,
    val seen: Long, val present: Boolean = true,
    val state: String = "pending", val hash: String = "", val offset: Long = 0,
    val attempts: Int = 0, val nextAttempt: Long = 0, val error: String = "",
    val library: String = "", val hashState: String = "", val hashOffset: Long = 0, val chunkBytes: Int = 4 * 1024 * 1024
)
data class Totals(val items: Long, val bytes: Long, val backed: Long, val backedBytes: Long, val transferred: Long, val failed: Long)
@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE `key`=:key") suspend fun get(key: String): Media?
    @Upsert suspend fun put(item: Media)
    @Upsert suspend fun put(items: List<Media>)
    @Query("SELECT * FROM media WHERE present=1 AND state IN ('pending','hashing','uploading','verifying') AND nextAttempt<=:now ORDER BY taken DESC, `key` LIMIT :limit")
    suspend fun queue(now: Long, limit: Int = 2): List<Media>
    @Query("SELECT * FROM media WHERE (:filter='all' OR state=:filter OR (:filter='pending' AND state IN ('hashing','pending'))) AND state!='excluded' ORDER BY taken DESC, `key`")
    fun page(filter: String): PagingSource<Int, Media>
    @Query("SELECT COUNT(*) AS items, COALESCE(SUM(size),0) AS bytes, COALESCE(SUM(CASE WHEN state='backed_up' THEN 1 ELSE 0 END),0) AS backed, COALESCE(SUM(CASE WHEN state='backed_up' THEN size ELSE 0 END),0) AS backedBytes, COALESCE(SUM(CASE WHEN state!='backed_up' THEN offset ELSE 0 END),0) AS transferred, COALESCE(SUM(CASE WHEN state='failed' THEN 1 ELSE 0 END),0) AS failed FROM media WHERE state!='excluded'")
    fun totals(): Flow<Totals>
    @Query("UPDATE media SET state='pending', error='', attempts=0, nextAttempt=0 WHERE state='failed' AND present=1") suspend fun retryFailures()
    @Query("UPDATE media SET present=0, state=CASE WHEN state='backed_up' THEN state ELSE 'failed' END, error=CASE WHEN state='backed_up' THEN '' ELSE 'Source no longer accessible' END WHERE seen<:scan AND uri LIKE :volumePattern")
    suspend fun reconcile(scan: Long, volumePattern: String)
    @Query("UPDATE media SET state=CASE WHEN present=1 THEN 'pending' ELSE 'failed' END, error=CASE WHEN present=1 THEN '' ELSE 'Source is no longer on phone; previous library retains its backup' END, library='', hash='', hashState='', hashOffset=0, offset=0, attempts=0, nextAttempt=0 WHERE state!='excluded'") suspend fun resetLibrary()
    @Query("UPDATE media SET present=0 WHERE `key` IN (:keys)") suspend fun markRemoved(keys: List<String>)
    @Query("SELECT * FROM media WHERE state='backed_up' AND present=1 AND library=:library ORDER BY taken ASC LIMIT 100")
    suspend fun cleanupCandidates(library: String): List<Media>
    @Query("SELECT COUNT(*) FROM media WHERE present=1 AND state IN ('pending','hashing','uploading','verifying')") suspend fun remaining(): Long
}
@Database(entities = [Media::class], version = 1, exportSchema = true)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    companion object {
        @Volatile private var instance: BackupDatabase? = null
        fun get(c: Context): BackupDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, BackupDatabase::class.java, "backup.db").build().also { instance = it }
        }
    }
}
