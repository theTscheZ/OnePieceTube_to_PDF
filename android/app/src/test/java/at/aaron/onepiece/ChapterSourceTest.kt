package at.aaron.onepiece

import org.junit.Assert.*
import org.junit.Test

class ChapterSourceTest {
    private val embedded = """<script>window.__data = {"chapter":{"pages":[{"url":"https:\/\/onepiece.tube\/upload\/01.jpg"},{"url":"https:\/\/onepiece.tube\/upload\/02-03.jpg"}]},"categoryEntries":[{"number":421},{"number":420},{"number":422}]};</script>"""
    @Test fun readsCurrentEmbeddedDataWithoutJavascript() {
        assertEquals(listOf("https://onepiece.tube/upload/01.jpg", "https://onepiece.tube/upload/02-03.jpg"), ChapterSource.embeddedImages(embedded))
        assertEquals(422, ChapterSource.latest(embedded))
    }
    @Test fun retainsLegacyPaginationAndResolvesRelativeImages() {
        val html = """<ul class="nav-pages"><li><a aria-label="Page 4"></a></li></ul><img class="img-responsive" src="/upload/page 1.jpg">"""
        assertEquals(4, ChapterSource.pageCount(html))
        assertEquals("https://onepiece.tube/upload/page%201.jpg", ChapterSource.image(html, "https://onepiece.tube/manga/kapitel/420/1"))
        assertNull(ChapterSource.embeddedImages(html))
    }
    @Test(expected = java.io.IOException::class) fun blockedPageFailsInsteadOfCreatingEmptyPdf() {
        ChapterSource.image("<html>Access denied</html>", "https://onepiece.tube")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsafeEmbeddedImages() {
        ChapterSource.embeddedImages("""<script>window.__data = {"chapter":{"pages":[{"url":"http://unsafe.example/1.jpg"}]}};</script>""")
    }
}
