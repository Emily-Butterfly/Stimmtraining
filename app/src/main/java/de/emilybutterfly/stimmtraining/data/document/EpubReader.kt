package de.emilybutterfly.stimmtraining.data.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * E-Books (EPUB 2 und 3): Die Kapitel stehen als XHTML im ZIP; ihre Reihenfolge gibt die
 * „Spine“ im Paketdokument (OPF) vor, auf das META-INF/container.xml zeigt. Jedes Kapitel liest
 * der [HtmlReader] – Überschriften, Absätze, Listen, fett und kursiv bleiben erhalten. Bilder,
 * Inhaltsverzeichnis-Seiten außerhalb der Spine und Anhänge mit linear="no" bleiben außen vor.
 */
internal object EpubReader {
    private const val CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val OPF = "http://www.idpf.org/2007/opf"

    /** Mehr Kapitel hat kein echtes Buch; begrenzt präparierte Dateien. */
    private const val MAX_CHAPTERS = 5_000

    /** Paketdokument und container.xml sind klein; mehr ist kein echtes E-Book. */
    private const val MAX_PACKAGE_BYTES = 4L * 1024 * 1024

    /** Entpackt insgesamt höchstens so viel – der angezeigte Text ist ohnehin begrenzt. */
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    private val CHAPTER_TYPES = setOf("application/xhtml+xml", "text/html", "application/html")
    private val CHAPTER_EXTENSIONS = setOf("xhtml", "html", "htm", "xht")

    const val NO_TEXT = "Das E-Book enthält keinen lesbaren Text (z. B. nur Bilder). Fotografiere die Seiten stattdessen."

    fun read(file: File, builder: TextDocumentBuilder, maxBytes: Long = ZipParts.MAX_BYTES) {
        val before = builder.remaining
        ZipFile(file).use { zip ->
            val chapters = chapters(zip)
            var total = 0L
            var anyChapter = false
            for (name in chapters) {
                if (builder.isFull) break
                val entry = zip.getEntry(name) ?: continue
                val limit = minOf(maxBytes, MAX_TOTAL_BYTES - total)
                if (limit <= 0) break
                val bytes = ZipParts.open(zip, entry.name, limit)!!.use { it.readBytes() }
                total += bytes.size
                anyChapter = true
                HtmlReader.read(TextDecoding.decode(bytes, declared = TextDecoding.declaredCharset(bytes)), builder)
                // Jedes Kapitel beginnt mit einem neuen Absatz, auch wenn eines offen endet.
                if (builder.currentBlockLength > 0) builder.endBlock()
            }
            if (!anyChapter) throw XmlPullParserException("Keine Kapitel gefunden")
        }
        if (builder.remaining == before) throw DocumentReadException(NO_TEXT)
    }

    /** Die Kapitel in Lesereihenfolge, als Namen im ZIP. */
    private fun chapters(zip: ZipFile): List<String> {
        val opf = packagePath(zip)
        val fromSpine = opf?.let { spine(zip, it) }.orEmpty()
        if (fromSpine.isNotEmpty()) return fromSpine
        // Ohne brauchbares Paketdokument: alle XHTML-Dateien in Namensreihenfolge.
        return zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in CHAPTER_EXTENSIONS }
            .map { it.name }
            .sorted()
            .take(MAX_CHAPTERS)
            .toList()
    }

    /** Wo das Paketdokument liegt: laut container.xml, sonst die erste .opf-Datei. */
    private fun packagePath(zip: ZipFile): String? {
        val fromContainer = ZipParts.open(zip, "META-INF/container.xml", MAX_PACKAGE_BYTES)?.use { input ->
            try {
                val parser = ZipParts.parser(input)
                var path: String? = null
                while (path == null) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> if (parser.name == "rootfile" && (parser.namespace == CONTAINER || parser.namespace.isEmpty())) {
                            val media = parser.getAttributeValue(null, "media-type")
                            if (media == null || media == "application/oebps-package+xml") path = parser.getAttributeValue(null, "full-path")
                        }
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
                path
            } catch (_: XmlPullParserException) {
                null
            }
        }
        return fromContainer?.takeIf { zip.getEntry(it) != null }
            ?: zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".opf", ignoreCase = true) }?.name
    }

    private class Item(val href: String, val mediaType: String?)

    private fun spine(zip: ZipFile, opfPath: String): List<String> {
        val base = opfPath.substringBeforeLast('/', "")
        val items = HashMap<String, Item>()
        val order = ArrayList<String>()
        ZipParts.open(zip, opfPath, MAX_PACKAGE_BYTES)?.use { input ->
            try {
                val parser = ZipParts.parser(input)
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> if (parser.namespace == OPF || parser.namespace.isEmpty()) {
                            when (parser.name) {
                                "item" -> {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    if (id != null && href != null && items.size < MAX_CHAPTERS * 4) {
                                        items[id] = Item(href, parser.getAttributeValue(null, "media-type"))
                                    }
                                }
                                "itemref" -> {
                                    val id = parser.getAttributeValue(null, "idref")
                                    // Anhänge wie Fußnotenseiten (linear="no") liest man nicht am Stück.
                                    if (id != null && parser.getAttributeValue(null, "linear") != "no" && order.size < MAX_CHAPTERS) order += id
                                }
                            }
                        }
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
            } catch (_: XmlPullParserException) {
                // Was bis dahin gelesen ist, reicht vielleicht; sonst greift die Namensreihenfolge.
            }
        }
        return order.mapNotNull { items[it] }
            .filter { item ->
                item.mediaType?.lowercase() in CHAPTER_TYPES ||
                    item.mediaType == null && item.href.substringBefore('#').substringAfterLast('.').lowercase() in CHAPTER_EXTENSIONS
            }
            .mapNotNull { resolve(base, it.href) }
            .distinct()
    }

    /** Ein Verweis relativ zum Paketdokument als Name im ZIP; null, wenn er aus dem ZIP hinauszeigt. */
    internal fun resolve(base: String, href: String): String? {
        val path = try {
            URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            href.substringBefore('#')
        }
        if (path.isEmpty() || path.contains(':')) return null
        val parts = ArrayList<String>()
        val start = if (path.startsWith("/")) emptyList() else base.split('/').filter { it.isNotEmpty() }
        for (part in start + path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    /** Für die Erkennung: Mit DRM verschlüsselte Kapitel lassen sich nicht lesen (Schriften dürfen verschleiert sein). */
    fun isDrmProtected(zip: ZipFile): Boolean {
        val encryption = ZipParts.open(zip, "META-INF/encryption.xml", MAX_PACKAGE_BYTES) ?: return false
        return try {
            encryption.use { input ->
                val parser = ZipParts.parser(input)
                var protected = false
                while (!protected && parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "CipherReference") {
                        val uri = parser.getAttributeValue(null, "URI").orEmpty().substringBefore('#').lowercase()
                        protected = uri.substringAfterLast('.') in CHAPTER_EXTENSIONS
                    }
                }
                protected
            }
        } catch (_: XmlPullParserException) {
            false
        } catch (_: IOException) {
            false
        }
    }
}
