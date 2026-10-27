package dev.personal.backup

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.*
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

class BackupApp : Application(), ImageLoaderFactory, Configuration.Provider {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("transfers", "Backup progress", NotificationManager.IMPORTANCE_LOW))
        Schedule.periodic(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (Settings(this@BackupApp).pairing() != null) Schedule.soon(this@BackupApp) }
        }) }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { Schedule.soon(this@BackupApp) }
        })
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { Schedule.soon(this@BackupApp) }
        })
    }
    override fun newImageLoader() = ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(24 * 1024 * 1024).build() }.diskCache(null).build()
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Keep WorkManager's JobScheduler IDs away from the manual transfer job (4001).
            .setJobSchedulerJobIdRange(10_000, 20_000)
            .build()
}
object Schedule {
    fun periodic(c: Context) {
        // NetworkType.CONNECTED can reject a LAN without internet; the engine explicitly binds Wi-Fi sockets.
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("automatic-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).build())
    }
    fun soon(c: Context) {
        if (Settings(c).paused || Settings(c).pairing() == null) return
        WorkManager.getInstance(c).enqueueUniqueWork("backup-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(3, TimeUnit.SECONDS).build())
    }
    fun manual(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork("backup-now")
        WorkManager.getInstance(c).cancelUniqueWork("automatic-backup")
        val job = JobInfo.Builder(4001, ComponentName(c, TransferJob::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build())
            .build()
        check(c.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule the transfer; keep the app open and try again" }
        periodic(c)
    }
    fun stop(c: Context) {
        c.getSystemService(JobScheduler::class.java).cancel(4001)
        WorkManager.getInstance(c).cancelUniqueWork("backup-now")
    }
}
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncEngine(applicationContext).run(false)
        return Result.success()
    }
}
class TransferJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        fun notification(state: SyncView, totals: Totals? = null): Notification {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val total = totals?.bytes ?: 0L
            val completed = totals?.let { (it.backedBytes + it.transferred).coerceIn(0, it.bytes) } ?: 0L
            val remaining = (total - completed).coerceAtLeast(0)
            val percent = if (total > 0) (completed * 100 / total).toInt() else 0
            val summary = when {
                total <= 0L -> state.message
                state.speed > 0L -> "${bytes(remaining)} left · ${bytes(state.speed)}/s"
                else -> "${bytes(remaining)} left · ${state.message}"
            }
            val activeNames = state.active.values.joinToString(", ") { it.name }
            val details = buildString {
                append(state.message)
                if (total > 0L) append("\n${bytes(completed)} of ${bytes(total)} transferred")
                if (activeNames.isNotEmpty()) append("\nUploading: $activeNames")
            }
            return Notification.Builder(this, "transfers")
                .setSmallIcon(R.drawable.ic_backup)
                .setContentTitle(if (total > 0L) "Homeward · $percent%" else "Homeward")
                .setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText(details))
                .setProgress(1000, if (total > 0L) (completed * 1000 / total).toInt() else 0, total <= 0L)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        }
        setNotification(params, 4001, notification(SyncView(message = "Preparing transfer · tap to pause")), JOB_END_NOTIFICATION_POLICY_REMOVE)
        work = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val notifier = launch {
                combine(SyncLive.state, BackupDatabase.get(applicationContext).media().totals()) { state, totals -> state to totals }.collect { (state, totals) ->
                    getSystemService(NotificationManager::class.java).notify(4001, notification(state, totals))
                }
            }
            try { SyncEngine(applicationContext).run(true) }
            finally { notifier.cancel(); withContext(NonCancellable + Dispatchers.Main) { jobFinished(params, false) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return !Settings(this).paused }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}
