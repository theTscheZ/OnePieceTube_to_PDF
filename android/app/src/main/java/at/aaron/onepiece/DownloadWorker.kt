package at.aaron.onepiece

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val automatic get() = inputData.getString("mode") == "auto"
    private val settings = AppSettings(context)
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private fun request(url: String) = client.newCall(Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Android) OnePieceTubeReader/1.0")
        .header("Referer", ChapterSource.BASE + "/").build())
    private fun html(url: String): String = request(url).execute().use {
        if (!it.isSuccessful) throw IOException("Website antwortet mit HTTP ${it.code}")
        it.body?.string() ?: throw IOException("Leere Antwort")
    }
    private fun checkStopped() { if (isStopped || (automatic && !settings.keepCurrent)) throw InterruptedException("Download abgebrochen") }
    private suspend fun status(text: String, done: Int, total: Int) {
        checkStopped()
        setProgress(workDataOf("message" to text, "done" to done, "total" to total))
        setForeground(notification(text, done, total))
    }
    private fun notification(text: String, done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Kapitel-Downloads", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val note = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0).addAction(0, "Abbrechen", cancel).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(42, note)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val library = Library(applicationContext)
        var completed = 0
        try {
            status("Kapitel werden ermittelt …", 0, 0)
            val mode = inputData.getString("mode") ?: "single"
            val startInput = inputData.getInt("start", 420)
            val latest by lazy { ChapterSource.latest(html("${ChapterSource.BASE}/manga/kapitel/420/1")) }
            val start = if (mode == "new") latest else if (mode == "all") 420 else startInput
            val end = when (mode) { "new" -> latest; "all", "from" -> latest; "range", "auto" -> inputData.getInt("end", start); else -> start }
            require(start in 1..100000 && end in start..100000) { "Ungültiger Kapitelbereich" }
            val total = end - start + 1
            for (chapter in start..end) {
                checkStopped()
                val target = File(library.directory, "OnePiece_$chapter.pdf")
                if (automatic && (chapter <= settings.downloadedThrough || library.files().any { ReadingOrder.chapterNumber(it.name) == chapter })) {
                    settings.rememberDownloaded(chapter)
                    continue
                }
                if (target.exists()) { completed++; status("Kapitel $chapter bereits vorhanden", completed, total); continue }
                val firstUrl = "${ChapterSource.BASE}/manga/kapitel/$chapter/1"
                val first = html(firstUrl)
                val urls = ChapterSource.embeddedImages(first) ?: (1..ChapterSource.pageCount(first)).map { page ->
                    checkStopped()
                    val url = "${ChapterSource.BASE}/manga/kapitel/$chapter/$page"
                    ChapterSource.image(if (page == 1) first else html(url), url)
                }
                val temporary = File(library.directory, "$id-$chapter.part")
                try {
                    JpegPdfWriter(temporary).use { document ->
                        urls.forEachIndexed { index, url ->
                            checkStopped()
                            status("Kapitel $chapter · Seite ${index + 1}/${urls.size}", completed, total)
                            val imageFile = File(applicationContext.cacheDir, "$id-image")
                            try {
                                request(url).execute().use { response ->
                                    if (!response.isSuccessful) throw IOException("Bild: HTTP ${response.code}")
                                    val body = response.body ?: throw IOException("Leeres Bild")
                                    body.byteStream().use { input -> imageFile.outputStream().use { out ->
                                        val buffer = ByteArray(8192); var bytes = 0L
                                        while (true) {
                                            checkStopped(); val n = input.read(buffer); if (n < 0) break
                                            bytes += n; require(bytes <= 40L * 1024 * 1024) { "Bild ist zu groß" }; out.write(buffer, 0, n)
                                        }
                                    } }
                                }
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(imageFile.path, bounds)
                                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Ungültiges Bild in Kapitel $chapter" }
                                val options = BitmapFactory.Options()
                                while (bounds.outWidth.toLong() * bounds.outHeight / (options.inSampleSize.coerceAtLeast(1).toLong() * options.inSampleSize.coerceAtLeast(1)) > 12_000_000) {
                                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                                }
                                val decoded = BitmapFactory.decodeFile(imageFile.path, options) ?: error("Bild konnte nicht gelesen werden")
                                val bitmap = if (decoded.hasAlpha()) {
                                    Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.RGB_565).also { opaque ->
                                        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                                        decoded.recycle()
                                    }
                                } else decoded
                                try {
                                    val jpeg = File(applicationContext.cacheDir, "$id-page.jpg")
                                    try {
                                        jpeg.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "Bild konnte nicht konvertiert werden" } }
                                        document.addPage(jpeg, bitmap.width, bitmap.height)
                                    } finally { jpeg.delete() }
                                } finally { bitmap.recycle() }
                            } finally { imageFile.delete() }
                        }
                        document.finish()
                    }
                    checkStopped()
                    library.pageCount(temporary)
                    val saved = synchronized(Library.LOCK) {
                        if (target.exists()) false else {
                            checkStopped()
                            check(temporary.renameTo(target)) { "PDF konnte nicht gespeichert werden" }
                            true
                        }
                    }
                    if (automatic) {
                        settings.rememberDownloaded(chapter)
                        if (saved) AutoUpdates.notifyDownloaded(applicationContext, chapter, target.name)
                    }
                } finally { temporary.delete() }
                completed++
                status("Kapitel $chapter gespeichert", completed, total)
            }
            Result.success(workDataOf("message" to "$completed Kapitel fertig"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (automatic && settings.keepCurrent) Result.retry()
            else Result.failure(workDataOf("message" to "${e.message ?: "Download fehlgeschlagen"} · $completed Kapitel fertig"))
        } finally { client.dispatcher.cancelAll() }
    }
    override suspend fun getForegroundInfo() = notification("Download wird gestartet …", 0, 0)
}
