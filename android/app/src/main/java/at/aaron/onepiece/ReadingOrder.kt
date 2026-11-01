package at.aaron.onepiece

/** Numeric filename ordering: chapter 9 comes before chapter 10, regardless of UI sorting. */
object ReadingOrder {
    private val parts = Regex("\\d+|\\D+")
    val names = Comparator<String> { a, b ->
        val left = parts.findAll(a.lowercase()).map { it.value }.toList()
        val right = parts.findAll(b.lowercase()).map { it.value }.toList()
        var result = 0
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            result = if (x.first().isDigit() && y.first().isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            if (result != 0) break
        }
        if (result != 0) result else left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private val chapterPattern = Regex("""(?i)^(?:(?:one[ _-]*piece)[ _-]*(?:(?:kapitel|chapter)[ _-]*)?|(?:kapitel|chapter)[ _-]*)?(\d+)(?:\D|$)""")

    /** Ignore file-copy suffixes such as "OnePiece_500 (2).pdf". */
    fun chapterNumber(fileName: String): Int? = chapterPattern.find(fileName)?.groupValues?.get(1)
        ?.toIntOrNull()?.takeIf { it in 1 until Int.MAX_VALUE }

    fun nextChapterNumber(fileNames: List<String>): Int =
        fileNames.mapNotNull(::chapterNumber).maxOrNull()?.plus(1) ?: 420

    data class Position(val chapter: Int, val page: Int)
    /** Int.MAX_VALUE means the last page; the renderer clamps it after opening the PDF. */
    fun next(position: Position, pageCount: Int, chapterCount: Int, forward: Boolean): Position? {
        val (chapter, page) = position
        if (chapter !in 0 until chapterCount || page !in 0 until pageCount) return null
        return if (forward) {
            when {
                page + 1 < pageCount -> Position(chapter, page + 1)
                chapter + 1 < chapterCount -> Position(chapter + 1, 0)
                else -> null
            }
        } else {
            when {
                page > 0 -> Position(chapter, page - 1)
                chapter > 0 -> Position(chapter - 1, Int.MAX_VALUE)
                else -> null
            }
        }
    }
}
