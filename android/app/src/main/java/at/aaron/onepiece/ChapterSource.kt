package at.aaron.onepiece

import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.IOException

/** The same selectors and page order as scraper.py, with absolute image URL resolution. */
object ChapterSource {
    const val BASE = "https://onepiece.tube"
    private fun data(html: String): JSONObject? {
        val script = Jsoup.parse(html).select("script").firstOrNull { it.data().contains("window.__data =") }?.data() ?: return null
        val json = script.substringAfter("window.__data =").trim().removeSuffix(";")
        return JSONObject(json)
    }
    fun embeddedImages(html: String): List<String>? {
        val pages = data(html)?.optJSONObject("chapter")?.optJSONArray("pages") ?: return null
        require(pages.length() in 1..500) { "Ungültige Seitenanzahl" }
        return (0 until pages.length()).map { index ->
            pages.getJSONObject(index).getString("url").replace(" ", "%20").also {
                require(it.startsWith("https://")) { "Unsichere Bildadresse" }
            }
        }
    }
    fun latest(html: String): Int {
        data(html)?.optJSONArray("categoryEntries")?.let { entries ->
            return (0 until entries.length()).maxOf { entries.getJSONObject(it).getInt("number") }
        }
        val doc = Jsoup.parse(html, BASE)
        val chapters = doc.select("a[href*=/manga/kapitel/]").mapNotNull {
            Regex("/manga/kapitel/(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        return chapters.maxOrNull() ?: doc.select("div.border-bottom").getOrNull(2)?.selectFirst("p")?.text()?.trim()?.toIntOrNull()
            ?: throw IOException("Kapitelnummer nicht gefunden. Die Website hat sich möglicherweise geändert.")
    }
    fun pageCount(html: String): Int = Jsoup.parse(html).select("ul.nav-pages li a[aria-label]")
        .mapNotNull { Regex("Page (\\d+)").find(it.attr("aria-label"))?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()?.coerceIn(1, 500) ?: 1
    fun image(html: String, url: String): String {
        val node = Jsoup.parse(html, url).selectFirst("img.img-responsive")
            ?: throw IOException("Keine Manga-Seite gefunden. Website eventuell geändert oder Zugriff blockiert.")
        return node.absUrl("src").replace(" ", "%20").takeIf { it.startsWith("https://") }
            ?: throw IOException("Keine sichere Bildadresse gefunden")
    }
}
