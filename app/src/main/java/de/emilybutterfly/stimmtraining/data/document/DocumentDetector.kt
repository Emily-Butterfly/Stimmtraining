package de.emilybutterfly.stimmtraining.data.document

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/** Erkennt das Format einer Datei am Inhalt; Name und MIME-Typ dienen nur als Hinweis. */
object DocumentDetector {
    private const val SNIFF_BYTES = 8192

    private const val SUPPORTED =
        "Unterstützt werden PDF, Word (.docx), OpenDocument (.odt), RTF, HTML, Markdown (.md) und Textdateien (.txt)."
    private const val UNKNOWN = "Dieses Dateiformat kann die App nicht lesen. $SUPPORTED"
    private const val DAMAGED = "Die Datei ist beschädigt und lässt sich nicht öffnen."
    private const val OLD_WORD = "Ältere Word-Dateien (.doc) kann die App nicht lesen. " +
        "Speichere den Text in Word oder LibreOffice als .docx, .odt oder PDF."
    private const val OLD_OFFICE = "Ältere Office-Dateien kann die App nicht lesen. $SUPPORTED"
    private const val IMAGE = "Das ist ein Bild. Fotos von Texten fügst du über „Kamera“ oder „Galerie“ hinzu."
    private const val SPREADSHEET = "Tabellen kann die App nicht als Text anzeigen. $SUPPORTED"
    private const val PRESENTATION = "Präsentationen kann die App nicht als Text anzeigen. Speichere sie als PDF."
    private const val EPUB = "E-Books (EPUB) kann die App nicht lesen. $SUPPORTED"
    private const val ZIP = "ZIP-Archive kann die App nicht lesen. Entpacke die Datei und wähle das Dokument darin aus."

    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
    private val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mkdn")
    private val MARKDOWN_MIME_TYPES = setOf("text/markdown", "text/x-markdown")
    private val ODT_MIME_TYPES = setOf("application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template")
    private val WORD_CONTENT_TYPES = listOf(
        "wordprocessingml.document.main+xml",
        "wordprocessingml.template.main+xml",
        "ms-word.document.macroEnabled.main+xml",
        "ms-word.template.macroEnabledTemplate.main+xml",
    )
    private val IMAGE_BRANDS = setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif", "avis")

    /** @throws UnsupportedDocumentException mit einer verständlichen Meldung für die Nutzerin */
    fun detect(file: File, displayName: String?, mimeType: String?): DocumentKind {
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(SNIFF_BYTES)
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            buffer.copyOf(size)
        }
        if (head.isEmpty()) throw UnsupportedDocumentException("Die Datei ist leer.")
        val extension = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            head.startsWith("%PDF") -> DocumentKind.Pdf
            head.startsWith("PK\u0003\u0004") -> detectZip(file)
            head.startsWith("{\\rtf") -> DocumentKind.Rtf
            head.startsWith(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) ->
                throw UnsupportedDocumentException(if (extension in setOf("xls", "ppt", "pps", "msg")) OLD_OFFICE else OLD_WORD)
            isImage(head) -> throw UnsupportedDocumentException(IMAGE)
            0.toByte() in head && !head.startsWith(0xFF, 0xFE) && !head.startsWith(0xFE, 0xFF) ->
                throw UnsupportedDocumentException(UNKNOWN)
            extension in HTML_EXTENSIONS || mime in HTML_MIME_TYPES -> DocumentKind.Html
            extension in MARKDOWN_EXTENSIONS || mime in MARKDOWN_MIME_TYPES -> DocumentKind.Markdown
            looksLikeHtml(head) -> DocumentKind.Html
            else -> DocumentKind.PlainText
        }
    }

    private fun detectZip(file: File): DocumentKind {
        val zip = try {
            ZipFile(file)
        } catch (_: IOException) {
            throw UnsupportedDocumentException(DAMAGED)
        }
        return try {
            zip.use { zipKind(it) }
        } catch (e: UnsupportedDocumentException) {
            throw e
        } catch (_: IOException) {
            throw UnsupportedDocumentException(DAMAGED)
        } catch (_: RuntimeException) {
            throw UnsupportedDocumentException(DAMAGED)
        }
    }

    private fun zipKind(zip: ZipFile): DocumentKind {
        if (zip.getEntry("word/document.xml") != null) return DocumentKind.Docx
        val contentTypes = smallEntry(zip, "[Content_Types].xml")
        if (contentTypes != null && WORD_CONTENT_TYPES.any { it in contentTypes }) return DocumentKind.Docx
        val mimetype = smallEntry(zip, "mimetype")?.trim()
        if (mimetype in ODT_MIME_TYPES && zip.getEntry("content.xml") != null) return DocumentKind.Odt
        throw UnsupportedDocumentException(
            when {
                mimetype == "application/epub+zip" -> EPUB
                mimetype?.startsWith("application/vnd.oasis.opendocument.spreadsheet") == true ||
                    zip.getEntry("xl/workbook.xml") != null -> SPREADSHEET
                mimetype?.startsWith("application/vnd.oasis.opendocument.presentation") == true ||
                    zip.getEntry("ppt/presentation.xml") != null -> PRESENTATION
                mimetype?.startsWith("application/vnd.oasis.opendocument") == true -> UNKNOWN
                else -> ZIP
            },
        )
    }

    /** Kleiner Textteil eines ZIP (höchstens 256 KB), z. B. "mimetype". */
    private fun smallEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(256 * 1024)
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            String(buffer, 0, size, Charsets.UTF_8)
        }
    }

    private fun isImage(head: ByteArray): Boolean =
        head.startsWith(0xFF, 0xD8, 0xFF) ||
            head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) ||
            head.startsWith("GIF8") ||
            head.startsWith("RIFF") && head.size >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" ||
            head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" &&
            String(head, 8, 4, Charsets.ISO_8859_1) in IMAGE_BRANDS

    /** HTML ohne passende Endung: beginnt mit `<!DOCTYPE html` oder `<html` (ggf. nach XML-Deklaration). */
    private fun looksLikeHtml(head: ByteArray): Boolean {
        var text = TextDecoding.decode(head, complete = false).trimStart()
        if (text.startsWith("<?xml")) text = text.substringAfter("?>", "").trimStart()
        return text.startsWith("<!doctype html", ignoreCase = true) || text.startsWith("<html", ignoreCase = true)
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
