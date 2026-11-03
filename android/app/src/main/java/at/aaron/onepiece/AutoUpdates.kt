package at.aaron.onepiece

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object AutoUpdates {
    const val CHECK = "automatic-chapter-check"
    const val DOWNLOAD = "chapter-download"
    const val TAG = "automatic-chapter-download"
    const val OPEN_CHAPTER = "openChapter"

    /** Never revisit old gaps or re-download deliberately deleted chapters. */
    fun chapters(latest: Int, present: List<String>, downloadedThrough: Int): IntRange? {
        require(latest in 1..100000) { "Ungültige neueste Kapitelnummer" }
        val highest = maxOf(downloadedThrough, present.mapNotNull(ReadingOrder::chapterNumber).maxOrNull() ?: 0)
        val first = if (highest == 0) latest.toLong() else highest.toLong() + 1L
        return if (first <= latest) first.toInt()..latest else null
    }

    fun schedule(context: Context) {
        val settings = AppSettings(context)
        val manager = WorkManager.getInstance(context)
        if (!settings.keepCurrent) {
            manager.cancelUniqueWork(CHECK)
            manager.cancelAllWorkByTag(TAG)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints(settings.wifiOnly)).build()
        manager.enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true).build()

    fun notifyDownloaded(context: Context, chapter: Int, fileName: String) {
        if (!AppSettings(context).keepCurrent) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("new-chapters", "Neue Kapitel", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra(OPEN_CHAPTER, fileName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, chapter, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(context, "new-chapters")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Neues Kapitel $chapter").setContentText("Heruntergeladen · Jetzt lesen")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setGroup("new-chapters").build()
        // An Android/user permission change between the check and delivery is harmless.
        try { manager.notify(chapter, note) } catch (_: SecurityException) { }
    }
}

/** Only checks metadata; actual downloads share the manual queue so they never overlap. */
class AutoCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = AppSettings(applicationContext)
        if (!settings.keepCurrent) return@withContext Result.success()
        val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder().url("${ChapterSource.BASE}/manga/kapitel/420/1")
                .header("User-Agent", "Mozilla/5.0 (Android) OnePieceTubeReader/1.1")
                .header("Referer", "${ChapterSource.BASE}/").build()
            val latest = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Website: HTTP ${response.code}" }
                ChapterSource.latest(response.body?.string() ?: error("Leere Antwort"))
            }
            val library = Library(applicationContext)
            val chapters = AutoUpdates.chapters(latest, library.files().map { it.name }, settings.downloadedThrough)
                ?: return@withContext Result.success()
            if (!settings.keepCurrent || isStopped) return@withContext Result.success()
            val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("mode" to "auto", "start" to chapters.first, "end" to chapters.last))
                .setConstraints(AutoUpdates.constraints(settings.wifiOnly)).addTag(AutoUpdates.TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(AutoUpdates.DOWNLOAD, ExistingWorkPolicy.KEEP, work)
            Result.success()
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { Result.retry()
        } finally { client.dispatcher.cancelAll() }
    }
}
