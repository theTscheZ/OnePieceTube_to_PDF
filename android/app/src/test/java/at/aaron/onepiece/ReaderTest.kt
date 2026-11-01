package at.aaron.onepiece

import org.junit.Assert.*
import org.junit.Test

class ReaderTest {
    @Test fun numericOrderingAvoidsChapterTenBeforeTwo() {
        assertEquals(listOf("OnePiece_2.pdf", "OnePiece_9.pdf", "OnePiece_10.pdf", "OnePiece_100.pdf"),
            listOf("OnePiece_100.pdf", "OnePiece_10.pdf", "OnePiece_9.pdf", "OnePiece_2.pdf").sortedWith(ReadingOrder.names))
    }
    @Test fun largeNumbersDoNotOverflow() {
        assertTrue(ReadingOrder.names.compare("9999999999999999999999.pdf", "10000000000000000000000.pdf") < 0)
    }
    @Test fun rightwardAdvanceCrossesPdfBoundary() {
        assertEquals(ReadingOrder.Position(1, 0), ReadingOrder.next(ReadingOrder.Position(0, 17), 18, 3, true))
        assertEquals(ReadingOrder.Position(0, 1), ReadingOrder.next(ReadingOrder.Position(0, 0), 18, 3, true))
    }
    @Test fun backwardCrossesToLastPageOfPreviousChapter() {
        assertEquals(ReadingOrder.Position(0, Int.MAX_VALUE), ReadingOrder.next(ReadingOrder.Position(1, 0), 12, 3, false))
    }
    @Test fun libraryBoundariesNeverWrap() {
        assertNull(ReadingOrder.next(ReadingOrder.Position(0, 0), 18, 3, false))
        assertNull(ReadingOrder.next(ReadingOrder.Position(2, 17), 18, 3, true))
        assertNull(ReadingOrder.next(ReadingOrder.Position(0, 3), 0, 3, true))
    }
    @Test fun downloadDefaultUsesLargestChapterRatherThanDateOrNameOrder() {
        assertEquals(501, ReadingOrder.nextChapterNumber(listOf("OnePiece_9.pdf", "OnePiece_500.pdf", "OnePiece_99.pdf")))
    }
    @Test fun downloadDefaultIgnoresDuplicateCopySuffixAndAcceptsImportedChapterNames() {
        assertEquals(501, ReadingOrder.nextChapterNumber(listOf("OnePiece_0500 (2).pdf", "Kapitel 499.pdf", "498.pdf")))
    }
    @Test fun unrelatedPdfNumbersDoNotBecomeChapterDefaults() {
        assertEquals(420, ReadingOrder.nextChapterNumber(listOf("Rechnung_2026.pdf", "Notizen.pdf")))
        assertEquals(420, ReadingOrder.nextChapterNumber(emptyList()))
    }
}
