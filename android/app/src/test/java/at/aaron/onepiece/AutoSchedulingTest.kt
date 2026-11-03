package at.aaron.onepiece

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoSchedulingTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        if (runCatching { WorkManager.getInstance(context) }.isFailure) {
            WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : Worker(appContext, workerParameters) { override fun doWork() = Result.success() }
            }).build())
        }
        manager = WorkManager.getInstance(context)
    }
    @Test fun reSchedulingKeepsOnePeriodicJobAndDisablingCancelsIt() {
        val settings = AppSettings(context)
        settings.keepCurrent = true
        AutoUpdates.schedule(context)
        // Reading after the operation waits for WorkManager's ordered database queue.
        val first = manager.getWorkInfosForUniqueWork(AutoUpdates.CHECK).get(10, TimeUnit.SECONDS)
            .single { !it.state.isFinished }
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
        settings.wifiOnly = false
        AutoUpdates.schedule(context)
        val updated = manager.getWorkInfosForUniqueWork(AutoUpdates.CHECK).get(10, TimeUnit.SECONDS)
            .single { !it.state.isFinished }
        assertEquals(first.id, updated.id)
        assertEquals(NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
        settings.keepCurrent = false
        AutoUpdates.schedule(context)
        assertTrue(manager.getWorkInfosForUniqueWork(AutoUpdates.CHECK).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
    }
    @Test fun disablingAutomaticUpdatesDoesNotCancelManualDownloads() {
        val manual = OneTimeWorkRequestBuilder<DownloadWorker>().setInitialDelay(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        manager.enqueueUniqueWork(AutoUpdates.DOWNLOAD, ExistingWorkPolicy.KEEP, manual).result.get(10, TimeUnit.SECONDS)
        AutoUpdates.schedule(context)
        assertNotEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(manual.id).get(10, TimeUnit.SECONDS)?.state)
        manager.cancelWorkById(manual.id).result.get(10, TimeUnit.SECONDS)
    }
}
