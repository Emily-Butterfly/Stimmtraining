package de.emilybutterfly.stimmtraining.data.document

import de.emilybutterfly.stimmtraining.data.document.StyleNames.isHeading
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Word-Dokumente (DOCX): der Fließtext aus word/document.xml mit Überschriften, Aufzählungen,
 * fett und kursiv. Kopf- und Fußzeilen, Fußnoten, Kommentare und gelöschter Text der
 * Änderungsverfolgung bleiben außen vor.
 */
internal object DocxReader {
    private const val DOCUMENT = "word/document.xml"
    private val W = setOf(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://purl.oclc.org/ooxml/wordprocessingml/main",
    )
    private const val MARKUP_COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    /** Inhalte ohne sichtbaren Fließtext oder mit überholtem Text (Änderungsverfolgung). */
    private val SKIPPED = setOf(
        "del", "moveFrom", "delText", "instrText", "sdtPr", "sdtEndPr", "pPrChange", "rPrChange", "sectPr",
        "tblPrChange", "tblGridChange", "trPrChange", "tcPrChange",
    )

    fun read(file: File, builder: TextDocumentBuilder, maxBytes: Long = ZipParts.MAX_BYTES) {
        ZipFile(file).use { zip ->
            val styles = ZipParts.open(zip, "word/styles.xml", maxBytes)?.use { input ->
                try {
                    readStyles(input)
                } catch (_: XmlPullParserException) {
                    emptyMap() // Ohne Formatvorlagen geht es auch, nur ungenauer.
                }
            }.orEmpty()
            val document = ZipParts.open(zip, mainPart(zip, maxBytes), maxBytes)
                ?: throw XmlPullParserException("$DOCUMENT fehlt")
            document.use { Body(ZipParts.parser(it), styles, builder).read() }
        }
    }

    /** Fast immer word/document.xml; sonst steht der Hauptteil in _rels/.rels. */
    private fun mainPart(zip: ZipFile, maxBytes: Long): String {
        if (zip.getEntry(DOCUMENT) != null) return DOCUMENT
        ZipParts.open(zip, "_rels/.rels", maxBytes)?.use { input ->
            val parser = ZipParts.parser(input)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship" &&
                    parser.getAttributeValue(null, "Type")?.endsWith("/officeDocument") == true
                ) {
                    return parser.getAttributeValue(null, "Target")?.removePrefix("/") ?: DOCUMENT
                }
            }
        }
        return DOCUMENT
    }

    private class Style {
        var name: String? = null
        var basedOn: String? = null
        var numbered: Boolean? = null
        var level: Int? = null
        var outline: Int? = null
        var bold: Boolean? = null
        var italic: Boolean? = null
    }

    private fun readStyles(input: InputStream): Map<String, Style> {
        val styles = HashMap<String, Style>()
        val parser = ZipParts.parser(input)
        var style: Style? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return styles
                XmlPullParser.END_TAG -> if (parser.name == "style") style = null
                XmlPullParser.START_TAG -> {
                    if (parser.namespace !in W) continue
                    val current = style
                    when (parser.name) {
                        "style" -> style = parser.value("styleId")?.let { id -> Style().also { styles[id] = it } }
                        // Bedingte Tabellenformate und Änderungen enthalten eigene Zeichenformate.
                        "tblStylePr", "pPrChange", "rPrChange", "docDefaults", "latentStyles" -> ZipParts.skip(parser)
                        "name" -> current?.name = parser.value("val")
                        "basedOn" -> current?.basedOn = parser.value("val")
                        "numId" -> current?.numbered = parser.value("val") != "0"
                        "ilvl" -> current?.level = parser.value("val")?.toIntOrNull()
                        "outlineLvl" -> current?.outline = parser.value("val")?.toIntOrNull()
                        "b" -> current?.bold = parser.isOn()
                        "i" -> current?.italic = parser.isOn()
                    }
                }
            }
        }
    }

    /** Ein offener Absatz samt Eigenschaften des aktuellen Textlaufs. */
    private class Paragraph {
        var styleId: String? = null
        var numId: String? = null
        var level: Int? = null
        var outline: Int? = null
        var hasText = false
        var markerUsed = false

        var bold: Boolean? = null
        var italic: Boolean? = null
        var charStyle: String? = null
        var hidden = false

        fun resetRun() {
            bold = null
            italic = null
            charStyle = null
            hidden = false
        }
    }

    private class Body(
        private val parser: XmlPullParser,
        private val styles: Map<String, Style>,
        private val builder: TextDocumentBuilder,
    ) {
        /** Absätze in Textfeldern stecken in einem äußeren Absatz, daher ein Stapel. */
        private val paragraphs = ArrayDeque<Paragraph>()

        fun read() {
            if (parser.name != "document" || parser.namespace !in W) throw XmlPullParserException("Kein Word-Dokument")
            while (!builder.isFull) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> return
                    XmlPullParser.START_TAG -> start()
                    XmlPullParser.END_TAG -> if (parser.name == "p" && parser.namespace in W) {
                        paragraphs.removeLastOrNull()?.let(::emit)
                    }
                }
            }
        }

        private fun start() {
            if (parser.namespace == MARKUP_COMPATIBILITY && parser.name == "Fallback") {
                // Ersatzdarstellung für alte Programme, enthält Textfelder ein zweites Mal.
                ZipParts.skip(parser)
                return
            }
            if (parser.namespace !in W) return
            val paragraph = paragraphs.lastOrNull()
            when (parser.name) {
                "p" -> {
                    // Bisherigen Text des äußeren Absatzes abschließen, damit die Reihenfolge stimmt.
                    paragraph?.let(::emit)
                    paragraphs.addLast(Paragraph())
                }
                in SKIPPED -> ZipParts.skip(parser)
                "pPr" -> if (paragraph != null) paragraphProperties(paragraph) else ZipParts.skip(parser)
                "r" -> paragraph?.resetRun()
                "rPr" -> if (paragraph != null) runProperties(paragraph) else ZipParts.skip(parser)
                "t" -> {
                    val text = parser.nextText()
                    if (paragraph != null) append(paragraph, text)
                }
                "tab", "ptab" -> paragraph?.let { append(it, "\t") }
                "noBreakHyphen" -> paragraph?.let { append(it, "-") }
                "br" -> when (parser.value("type")) {
                    // Seiten- und Spaltenumbruch: Absatz hier teilen statt Leerzeile.
                    "page", "column" -> paragraph?.let(::emit)
                    else -> paragraph?.let { append(it, "\n") }
                }
                "cr" -> paragraph?.let { append(it, "\n") }
            }
        }

        private fun paragraphProperties(paragraph: Paragraph) {
            val depth = parser.depth
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unerwartetes Ende")
                    XmlPullParser.END_TAG -> if (parser.depth == depth) return
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "pStyle" -> paragraph.styleId = parser.value("val")
                        "numId" -> paragraph.numId = parser.value("val")
                        "ilvl" -> paragraph.level = parser.value("val")?.toIntOrNull()
                        "outlineLvl" -> paragraph.outline = parser.value("val")?.toIntOrNull()
                        // Zeichenformat der Absatzmarke, alte Fassungen, Abschnittsdaten.
                        "rPr", "pPrChange", "sectPr" -> ZipParts.skip(parser)
                    }
                }
            }
        }

        private fun runProperties(paragraph: Paragraph) {
            val depth = parser.depth
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unerwartetes Ende")
                    XmlPullParser.END_TAG -> if (parser.depth == depth) return
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "b" -> paragraph.bold = parser.isOn()
                        "i" -> paragraph.italic = parser.isOn()
                        "rStyle" -> paragraph.charStyle = parser.value("val")
                        "vanish" -> paragraph.hidden = parser.isOn()
                        "rPrChange" -> ZipParts.skip(parser)
                    }
                }
            }
        }

        private fun append(paragraph: Paragraph, text: String) {
            if (paragraph.hidden || text.isEmpty()) return
            val charStyle = paragraph.charStyle
            val bold = paragraph.bold ?: inherited(charStyle) { it.bold } ?: charStyleHint(charStyle, "strong", "fett")
            val italic = paragraph.italic ?: inherited(charStyle) { it.italic } ?: charStyleHint(charStyle, "emphasis", "hervorhebung")
            builder.append(text, bold, italic)
            if (text.isNotBlank()) paragraph.hasText = true
        }

        /** Gibt den bisher gesammelten Text des Absatzes als Block aus. */
        private fun emit(paragraph: Paragraph) {
            val (style, level) = blockStyle(paragraph)
            val item = style == BlockStyle.ListItem
            val marker = if (item && !paragraph.markerUsed && paragraph.hasText) "•" else null
            if (marker != null) paragraph.markerUsed = true
            builder.endBlock(style, marker, if (item) level else 0)
            paragraph.hasText = false
        }

        private fun blockStyle(paragraph: Paragraph): Pair<BlockStyle, Int> {
            paragraph.outline?.takeIf { it in 0..8 }?.let { return StyleNames.heading(it + 1) to 0 }
            var named: BlockStyle? = null
            var numbered = paragraph.numId?.let { it != "0" }
            var level = paragraph.level
            var id = paragraph.styleId
            var steps = 0
            while (id != null && steps++ < 16) {
                val style = styles[id]
                named = StyleNames.blockStyle(id) ?: StyleNames.blockStyle(style?.name)
                    ?: style?.outline?.takeIf { it in 0..8 }?.let { StyleNames.heading(it + 1) }
                if (named != null) break
                if (numbered == null) numbered = style?.numbered
                if (level == null) level = style?.level
                id = style?.basedOn
            }
            return when {
                named != null && named.isHeading -> named to 0
                numbered == true -> BlockStyle.ListItem to (level ?: 0)
                else -> (named ?: BlockStyle.Paragraph) to 0
            }
        }

        /** Eigenschaft einer Zeichenformatvorlage, auch geerbt über "basedOn". */
        private fun inherited(id: String?, property: (Style) -> Boolean?): Boolean? {
            var current = id
            var steps = 0
            while (current != null && steps++ < 16) {
                val style = styles[current] ?: return null
                property(style)?.let { return it }
                current = style.basedOn
            }
            return null
        }

        /** Ohne styles.xml: "Strong"/"Fett" bzw. "Emphasis"/"Hervorhebung" am Namen erkennen. */
        private fun charStyleHint(id: String?, english: String, german: String): Boolean {
            val key = (styles[id]?.name ?: id)?.lowercase(Locale.ROOT) ?: return false
            return key == english || key == german
        }
    }

    private fun XmlPullParser.value(name: String): String? = getAttributeValue(null, name)

    /** `<w:b/>` ist an, `<w:b w:val="0"/>` (bzw. "false", "off") aus. */
    private fun XmlPullParser.isOn(): Boolean = value("val")?.lowercase(Locale.ROOT) !in setOf("0", "false", "off")
}
