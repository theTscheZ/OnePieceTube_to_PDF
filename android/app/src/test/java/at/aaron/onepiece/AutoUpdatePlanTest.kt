package at.aaron.onepiece

import org.junit.Assert.*
import org.junit.Test

class AutoUpdatePlanTest {
    @Test fun emptyLibraryDownloadsOnlyLatest() {
        assertEquals(1165..1165, AutoUpdates.chapters(1165, emptyList(), 0))
    }
    @Test fun downloadsEverythingAfterHighestPresentChapter() {
        assertEquals(501..503, AutoUpdates.chapters(503, listOf("OnePiece_420.pdf", "OnePiece_500.pdf"), 0))
    }
    @Test fun copySuffixAndFilenameOrderingDoNotChangeTheHighestChapter() {
        assertEquals(501..501, AutoUpdates.chapters(501, listOf("OnePiece_500 (2).pdf", "OnePiece_99.pdf"), 0))
    }
    @Test fun upToDateOrAheadDoesNothing() {
        assertNull(AutoUpdates.chapters(500, listOf("OnePiece_500.pdf"), 0))
        assertNull(AutoUpdates.chapters(499, listOf("OnePiece_500.pdf"), 0))
    }
    @Test fun deletedCompletedChaptersAreNotDownloadedAgain() {
        assertNull(AutoUpdates.chapters(503, listOf("OnePiece_500.pdf"), 503))
        assertNull(AutoUpdates.chapters(503, emptyList(), 503))
    }
    @Test fun failureAfterOneSuccessResumesAtTheFailedChapter() {
        assertEquals(502..503, AutoUpdates.chapters(503, listOf("OnePiece_500.pdf", "OnePiece_501.pdf"), 501))
    }
    @Test fun newerManualDownloadBecomesTheStartingPoint() {
        assertEquals(511..512, AutoUpdates.chapters(512, listOf("OnePiece_510.pdf"), 503))
    }
    @Test fun unrelatedImportsStartAtLatest() {
        assertEquals(503..503, AutoUpdates.chapters(503, listOf("Notizen.pdf"), 0))
    }
    @Test fun largeImportedNumberCannotOverflow() {
        assertNull(AutoUpdates.chapters(503, listOf("OnePiece_2147483646.pdf"), 0))
    }
}
