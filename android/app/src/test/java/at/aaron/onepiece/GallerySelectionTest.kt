package at.aaron.onepiece

import org.junit.Assert.*
import org.junit.Test

class GallerySelectionTest {
    private val keys = (0..9).map { "chapter-$it" }
    @Test fun fastSweepIncludesSkippedChaptersAndShrinksWhenReversed() {
        val drag = GallerySelection(keys, emptySet(), 2)
        assertEquals(keys.subList(2, 9).toSet(), drag.at(8))
        assertEquals(keys.subList(2, 5).toSet(), drag.at(4))
        assertEquals(keys.subList(0, 3).toSet(), drag.at(0))
    }
    @Test fun reversingRestoresTheSelectionFromBeforeTheGesture() {
        val before = setOf(keys[0], keys[4], keys[9])
        val drag = GallerySelection(keys, before, 2)
        assertEquals(before + keys.subList(2, 8), drag.at(7))
        assertEquals(before + keys[2], drag.at(2))
    }
    @Test fun startingOnSelectedChapterDeselectsAndSupportsReversal() {
        val before = keys.toSet()
        val drag = GallerySelection(keys, before, 4)
        assertEquals(before - keys.subList(4, 9).toSet(), drag.at(8))
        assertEquals(before - setOf(keys[4], keys[5]), drag.at(5))
    }
    @Test fun revisitingSameChapterNeverTogglesItRepeatedly() {
        val drag = GallerySelection(keys, setOf(keys[1]), 3)
        assertEquals(drag.at(7), drag.at(7))
        assertTrue(keys[1] in drag.at(7))
    }
    @Test fun extremeIndicesClampToTheCollection() {
        val drag = GallerySelection(keys, emptySet(), 3)
        assertEquals(keys.subList(3, 10).toSet(), drag.at(Int.MAX_VALUE))
        assertEquals(keys.subList(0, 4).toSet(), drag.at(Int.MIN_VALUE))
    }
    @Test fun centerDoesNotScrollAndEdgesScrollInOppositeDirections() {
        assertEquals(0f, GallerySelection.scrollVelocity(400f, 800f, 100f, 1000f), .001f)
        assertEquals(-1000f, GallerySelection.scrollVelocity(0f, 800f, 100f, 1000f), .001f)
        assertEquals(1000f, GallerySelection.scrollVelocity(800f, 800f, 100f, 1000f), .001f)
    }
    @Test fun scrollAcceleratesWithEdgeDepthAndIsBoundedOutsideTheGrid() {
        assertEquals(250f, GallerySelection.scrollVelocity(750f, 800f, 100f, 1000f), .001f)
        assertTrue(GallerySelection.scrollVelocity(790f, 800f, 100f, 1000f) > 250f)
        assertEquals(1000f, GallerySelection.scrollVelocity(1200f, 800f, 100f, 1000f), .001f)
        assertEquals(-1000f, GallerySelection.scrollVelocity(-200f, 800f, 100f, 1000f), .001f)
    }
    @Test fun emptyViewportAndInvalidFingerPositionNeverScroll() {
        assertEquals(0f, GallerySelection.scrollVelocity(20f, 0f, 100f, 1000f), .001f)
        assertEquals(0f, GallerySelection.scrollVelocity(Float.NaN, 800f, 100f, 1000f), .001f)
    }
}
