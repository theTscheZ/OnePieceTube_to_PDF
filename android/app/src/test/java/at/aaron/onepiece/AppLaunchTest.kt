package at.aaron.onepiece

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppLaunchTest {
    @Before fun initializeBackgroundWork() {
        // Robolectric does not run AndroidX Startup's content-provider initialization.
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (runCatching { WorkManager.getInstance(context) }.isFailure) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }
    @Test fun launcherIconInflatesAndDraws() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val icon = requireNotNull(context.getDrawable(R.mipmap.ic_launcher))
        icon.setBounds(0, 0, 192, 192)
        icon.draw(Canvas(Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)))
    }
    @Test fun activityStartsWithAnEmptyLibrary() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertFalse(controller.get().isFinishing)
        assertNotNull(controller.get().window.decorView)
        controller.saveInstanceState(Bundle())
        controller.pause().stop().destroy()
    }
    @Test fun activityStartsEvenWithAnUnreadableStoredPdf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chapter = File(File(context.filesDir, "chapters").apply { mkdirs() }, "OnePiece_420.pdf")
        chapter.writeText("%PDF-1.4\nIncomplete test document")
        try {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertFalse(controller.get().isFinishing)
            assertTrue(chapter.exists())
            controller.pause().stop().destroy()
        } finally { chapter.delete() }
    }
}
