package at.aaron.onepiece

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Streams JPEG pages directly to disk, retaining no chapter-sized bitmap collection in RAM. */
class JpegPdfWriter(file: File) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private val offsets = mutableMapOf<Int, Long>()
    private val pages = mutableListOf<Int>()
    private var finished = false
    init {
        output.setLength(0)
        output.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(Charsets.ISO_8859_1))
    }
    private fun text(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
    private fun begin(id: Int) { offsets[id] = output.filePointer; text("$id 0 obj\n") }
    fun addPage(jpeg: File, width: Int, height: Int) {
        check(!finished)
        require(width > 0 && height > 0 && jpeg.length() > 0)
        val imageId = 3 + pages.size * 3
        val contentId = imageId + 1
        val pageId = imageId + 2
        begin(imageId)
        text("<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length()} >>\nstream\n")
        jpeg.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        }
        text("\nendstream\nendobj\n")
        val commands = "q\n$width 0 0 $height 0 0 cm\n/Im0 Do\nQ\n"
        begin(contentId)
        text("<< /Length ${commands.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${commands}endstream\nendobj\n")
        begin(pageId)
        text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n")
        pages += pageId
    }
    fun finish() {
        check(!finished && pages.isNotEmpty())
        begin(1); text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2); text("<< /Type /Pages /Count ${pages.size} /Kids [${pages.joinToString(" ") { "$it 0 R" }}] >>\nendobj\n")
        val xref = output.filePointer
        val size = offsets.size + 1
        text("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) text(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.getValue(id)))
        text("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        finished = true
    }
    override fun close() { output.close() }
}
