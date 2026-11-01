package at.aaron.onepiece

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class JpegPdfWriterTest {
    @Test fun writesValidOffsetsAndPageTreeForMultiplePages() {
        val image = File.createTempFile("page", ".jpg")
        val pdf = File.createTempFile("chapter", ".pdf")
        try {
            image.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0xff.toByte(), 0xd9.toByte()))
            JpegPdfWriter(pdf).use { writer ->
                writer.addPage(image, 800, 1200); writer.addPage(image, 1600, 1200); writer.finish()
            }
            val data = pdf.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(data.startsWith("%PDF-1.4"))
            assertTrue(data.contains("/Count 2 /Kids [5 0 R 8 0 R]"))
            assertTrue(data.contains("/MediaBox [0 0 1600 1200]"))
            val xref = data.substringAfterLast("startxref\n").substringBefore('\n').toInt()
            assertTrue(data.substring(xref).startsWith("xref\n0 9\n"))
            val entries = data.substring(xref).lines().drop(3).take(8)
            entries.forEachIndexed { index, line ->
                val offset = line.substring(0, 10).toInt()
                assertTrue("Object ${index + 1}", data.substring(offset).startsWith("${index + 1} 0 obj\n"))
            }
        } finally { image.delete(); pdf.delete() }
    }
}
