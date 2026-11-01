package at.aaron.onepiece

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

class Library(private val context: Context) {
    val directory = File(context.filesDir, "chapters").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
    fun files(): List<File> = directory.listFiles()?.filter { it.extension.equals("pdf", true) }
        ?.sortedWith { a, b -> ReadingOrder.names.compare(a.name, b.name) } ?: emptyList()
    fun savedPage(file: File) = prefs.getInt("page:${file.name}", 0)
    fun savePage(file: File, page: Int) {
        prefs.edit().putInt("page:${file.name}", page).putString("last", file.name).apply()
    }
    fun lastFile() = prefs.getString("last", null)?.let { name -> files().find { it.name == name } }
    fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer -> renderer.pageCount.also { require(it > 0) { "PDF enthält keine Seiten" } } }
    }
    fun importPdf(uri: Uri): File {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Import.pdf"
        val temporary = File(directory, "${UUID.randomUUID()}.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                ?: error("Datei konnte nicht geöffnet werden")
            pageCount(temporary)
            synchronized(LOCK) {
                val base = safeName(name)
                var target = File(directory, base)
                var n = 2
                while (target.exists()) target = File(directory, "${base.removeSuffix(".pdf")} (${n++}).pdf")
                check(temporary.renameTo(target)) { "PDF konnte nicht gespeichert werden" }
                return target
            }
        } finally { temporary.delete() }
    }
    fun rename(file: File, name: String) = synchronized(LOCK) {
        val target = File(directory, safeName(name))
        if (target == file) return@synchronized
        require(!target.exists()) { "Dieser Name ist bereits vergeben" }
        check(file.renameTo(target)) { "Umbenennen fehlgeschlagen" }
        val edit = prefs.edit().putInt("page:${target.name}", savedPage(file)).remove("page:${file.name}")
        if (prefs.getString("last", null) == file.name) edit.putString("last", target.name)
        edit.apply()
    }
    fun delete(file: File) {
        check(file.delete()) { "Löschen fehlgeschlagen" }
        val edit = prefs.edit().remove("page:${file.name}")
        if (prefs.getString("last", null) == file.name) edit.remove("last")
        edit.apply()
    }
    companion object {
        val LOCK = Any()
        fun safeName(name: String): String {
            val clean = name.trim().replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
                .replace(Regex("(?i)\\.pdf$"), "").trim().take(160)
            require(clean.isNotBlank() && clean != "." && clean != "..") { "Bitte einen gültigen Dateinamen eingeben" }
            return "$clean.pdf"
        }
    }
}
