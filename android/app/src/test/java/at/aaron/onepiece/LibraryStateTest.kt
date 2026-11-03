package at.aaron.onepiece

import android.content.Context
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LibraryStateTest {
    private lateinit var context: Context
    private lateinit var library: Library
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("reading", 0).edit().clear().commit()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        library = Library(context)
        library.directory.listFiles()?.forEach { it.delete() }
    }
    private fun file(name: String = "OnePiece_500.pdf") = File(library.directory, name).apply { writeText("test") }
    @Test fun newChapterIsUnreadAndReadingTheLastPageMarksItRead() {
        val f = file()
        assertEquals(ReadStatus.UNREAD, library.status(f))
        library.savePage(f, 0, 3)
        assertEquals(ReadStatus.STARTED, library.status(f))
        library.savePage(f, 2, 3)
        assertEquals(ReadStatus.READ, library.status(f))
        library.savePage(f, 0, 3)
        assertEquals(ReadStatus.READ, library.status(f))
    }
    @Test fun legacyProgressIsPreservedAsStarted() {
        val f = file()
        context.getSharedPreferences("reading", 0).edit().putInt("page:${f.name}", 9).commit()
        assertEquals(ReadStatus.STARTED, library.status(f))
        assertEquals(9, library.savedPage(f))
    }
    @Test fun markingUnreadResetsResumePosition() {
        val f = file()
        library.savePage(f, 4, 10)
        library.markStatus(f, ReadStatus.UNREAD)
        assertEquals(ReadStatus.UNREAD, library.status(f))
        assertEquals(0, library.savedPage(f))
        assertNull(library.lastFile())
    }
    @Test fun renameAndDeletionCarryAndRemoveReadingState() {
        val f = file()
        library.savePage(f, 9, 10)
        library.rename(f, "Kapitel 500")
        val renamed = File(library.directory, "Kapitel 500.pdf")
        assertEquals(ReadStatus.READ, library.status(renamed))
        assertEquals(9, library.savedPage(renamed))
        assertEquals(renamed, library.lastFile())
        library.delete(renamed)
        assertEquals(ReadStatus.UNREAD, library.status(renamed))
        assertNull(library.lastFile())
    }
    @Test fun settingsSurviveRecreationAndWatermarkNeverMovesBackwards() {
        val s = AppSettings(context)
        assertFalse(s.keepCurrent)
        assertTrue(s.wifiOnly)
        s.keepCurrent = true; s.accent = Accent.LAVENDER
        s.rememberDownloaded(501); s.rememberDownloaded(500)
        val restored = AppSettings(context)
        assertTrue(restored.keepCurrent)
        assertEquals(Accent.LAVENDER, restored.accent)
        assertEquals(501, restored.downloadedThrough)
    }
    @Test fun notificationOpensTheDownloadedChapterAndIsSuppressedWhenDisabled() {
        val manager = context.getSystemService(NotificationManager::class.java)
        AutoUpdates.notifyDownloaded(context, 501, "OnePiece_501.pdf")
        assertNull(shadowOf(manager).getNotification(501))
        AppSettings(context).keepCurrent = true
        AutoUpdates.notifyDownloaded(context, 501, "OnePiece_501.pdf")
        val notification = requireNotNull(shadowOf(manager).getNotification(501))
        assertEquals("Neues Kapitel 501", notification.extras.getString("android.title"))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("OnePiece_501.pdf", intent.getStringExtra(AutoUpdates.OPEN_CHAPTER))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }
    @Test @Config(sdk = [35]) fun deniedNotificationPermissionDoesNotBlockTheDownloadState() {
        val settings = AppSettings(context)
        settings.keepCurrent = true
        settings.rememberDownloaded(501)
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AutoUpdates.notifyDownloaded(context, 501, "OnePiece_501.pdf")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(shadowOf(manager).getNotification(501))
        assertEquals(501, AppSettings(context).downloadedThrough)
    }

}
