package de.emilybutterfly.stimmtraining.data.document

import de.emilybutterfly.stimmtraining.data.document.StyleNames.isHeading
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.util.zip.ZipFile

/**
 * OpenDocument-Text (ODT, z. B. aus LibreOffice): Fließtext aus content.xml mit Überschriften,
 * Listen, fett und kursiv. Fett/kursiv steckt in Formatvorlagen – den automatischen in
 * content.xml und den benannten in styles.xml. Fußnoten, Kommentare und die Liste der
 * Änderungen bleiben außen vor.
 */
internal object OdtReader {
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"

    private val SKIPPED_TEXT = setOf(
        "note", "tracked-changes", "sequence-decls", "variable-decls", "user-field-decls", "dde-connection-decls",
    )
    private val SKIPPED_OFFICE = setOf("annotation", "forms", "scripts", "event-listeners")

    fun read(file: File, builder: TextDocumentBuilder, maxBytes: Long = ZipParts.MAX_BYTES) {
        ZipFile(file).use { zip ->
            val styles = Styles()
            ZipParts.open(zip, "styles.xml", maxBytes)?.use { input ->
                try {
                    readCommonStyles(ZipParts.parser(input), styles)
                } catch (_: XmlPullParserException) {
                    // Ohne benannte Formatvorlagen geht es auch, nur ungenauer.
                }
            }
            val content = ZipParts.open(zip, "content.xml", maxBytes) ?: throw XmlPullParserException("content.xml fehlt")
            content.use { Body(ZipParts.parser(it), styles, builder).read() }
        }
    }

    private class Style(val parent: String?, val displayName: String?) {
        var bold: Boolean? = null
        var italic: Boolean? = null
    }

    /** Absatz- und Zeichenvorlagen getrennt, die Namensräume der Familien überschneiden sich. */
    private class Styles {
        val paragraph = HashMap<String, Style>()
        val text = HashMap<String, Style>()

        /** Vorlage und ihre Eltern, innerste zuerst. */
        fun chain(map: Map<String, Style>, name: String?): Sequence<Pair<String, Style?>> = sequence {
            var current = name
            var steps = 0
            while (current != null && steps++ < 16) {
                val style = map[current]
                yield(current to style)
                current = style?.parent
            }
        }

        fun bold(map: Map<String, Style>, name: String?): Boolean? = chain(map, name).firstNotNullOfOrNull { it.second?.bold }
        fun italic(map: Map<String, Style>, name: String?): Boolean? = chain(map, name).firstNotNullOfOrNull { it.second?.italic }

        fun blockStyle(name: String?): BlockStyle? = chain(paragraph, name).firstNotNullOfOrNull { (styleName, style) ->
            StyleNames.blockStyle(styleName) ?: StyleNames.blockStyle(style?.displayName)
        }
    }

    /** styles.xml: nur die benannten Vorlagen; die automatischen dort gelten für Kopf- und Fußzeilen. */
    private fun readCommonStyles(parser: XmlPullParser, styles: Styles) {
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> if (parser.namespace == OFFICE) {
                    when (parser.name) {
                        "styles" -> readStyleSection(parser, styles)
                        "automatic-styles", "master-styles", "font-face-decls" -> ZipParts.skip(parser)
                    }
                }
            }
        }
    }

    private fun readStyleSection(parser: XmlPullParser, styles: Styles) {
        val depth = parser.depth
        var style: Style? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unerwartetes Ende")
                XmlPullParser.END_TAG -> {
                    if (parser.depth == depth) return
                    if (parser.namespace == STYLE && parser.name == "style") style = null
                }
                XmlPullParser.START_TAG -> if (parser.namespace == STYLE) {
                    when (parser.name) {
                        "style" -> {
                            val name = parser.getAttributeValue(STYLE, "name")
                            val map = when (parser.getAttributeValue(STYLE, "family")) {
                                "paragraph" -> styles.paragraph
                                "text" -> styles.text
                                else -> null
                            }
                            style = if (name != null && map != null) {
                                Style(parser.getAttributeValue(STYLE, "parent-style-name"), parser.getAttributeValue(STYLE, "display-name"))
                                    .also { map[name] = it }
                            } else {
                                null
                            }
                        }
                        "text-properties" -> style?.let {
                            parser.getAttributeValue(FO, "font-weight")?.let { weight ->
                                it.bold = weight == "bold" || (weight.toIntOrNull() ?: 0) >= 600
                            }
                            parser.getAttributeValue(FO, "font-style")?.let { fontStyle ->
                                it.italic = fontStyle == "italic" || fontStyle == "oblique"
                            }
                        }
                    }
                }
            }
        }
    }

    private class Paragraph(val style: BlockStyle, val level: Int, bold: Boolean, italic: Boolean) {
        /** Formate der offenen text:span, das unterste ist das des Absatzes. */
        val formats = ArrayDeque(listOf(bold to italic))
        var hasText = false

        /** Leerraum wird in ODF wie in HTML zusammengefasst; am Absatzanfang entfällt er. */
        var afterSpace = true
    }

    private class Body(
        private val parser: XmlPullParser,
        private val styles: Styles,
        private val builder: TextDocumentBuilder,
    ) {
        private val paragraphs = ArrayDeque<Paragraph>()

        /** Je offenem Element: zählt sein Textinhalt zum Text? */
        private val visible = ArrayDeque<Boolean>()
        private var listDepth = 0

        /** Je offenem Listenpunkt: steht sein Aufzählungszeichen noch aus? */
        private val pendingMarkers = ArrayDeque<Boolean>()

        fun read() {
            if (parser.name != "document-content" || parser.namespace != OFFICE) {
                throw XmlPullParserException("Kein OpenDocument-Inhalt")
            }
            while (!builder.isFull) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> return
                    XmlPullParser.START_TAG -> start()
                    XmlPullParser.END_TAG -> end()
                    XmlPullParser.TEXT -> if (visible.lastOrNull() == true) paragraphs.lastOrNull()?.let { text(it, parser.text) }
                }
            }
        }

        private fun start() {
            val namespace = parser.namespace
            val name = parser.name
            val skip = when (namespace) {
                OFFICE -> name in SKIPPED_OFFICE
                TEXT -> name in SKIPPED_TEXT || name.endsWith("-source")
                else -> false
            }
            if (skip) return ZipParts.skip(parser)
            if (namespace == OFFICE && name == "automatic-styles") return readStyleSection(parser, styles)
            visible.addLast(namespace == TEXT)
            if (namespace != TEXT) return
            val paragraph = paragraphs.lastOrNull()
            when (name) {
                "p", "h" -> startParagraph(name == "h")
                "span" -> paragraph?.let {
                    val styleName = parser.getAttributeValue(TEXT, "style-name")
                    val (bold, italic) = it.formats.last()
                    it.formats.addLast(
                        (styles.bold(styles.text, styleName) ?: bold) to (styles.italic(styles.text, styleName) ?: italic),
                    )
                }
                "s" -> paragraph?.let {
                    val count = parser.getAttributeValue(TEXT, "c")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                    append(it, " ".repeat(count))
                }
                "tab" -> paragraph?.let { append(it, "\t") }
                "line-break" -> paragraph?.let { append(it, "\n") }
                "list" -> listDepth++
                "list-item" -> pendingMarkers.addLast(true)
                "list-header" -> pendingMarkers.addLast(false)
            }
        }

        private fun end() {
            visible.removeLastOrNull()
            if (parser.namespace != TEXT) return
            when (parser.name) {
                "p", "h" -> paragraphs.removeLastOrNull()?.let(::emit)
                "span" -> paragraphs.lastOrNull()?.formats?.let { if (it.size > 1) it.removeLast() }
                "list" -> listDepth = (listDepth - 1).coerceAtLeast(0)
                "list-item", "list-header" -> pendingMarkers.removeLastOrNull()
            }
        }

        private fun startParagraph(heading: Boolean) {
            // Bisherigen Text des äußeren Absatzes (z. B. vor einem Textrahmen) abschließen.
            paragraphs.lastOrNull()?.let(::emit)
            val styleName = parser.getAttributeValue(TEXT, "style-name")
            val named = styles.blockStyle(styleName)
            val style = when {
                heading -> StyleNames.heading(parser.getAttributeValue(TEXT, "outline-level")?.toIntOrNull() ?: 1)
                named != null && named.isHeading -> named
                listDepth > 0 -> BlockStyle.ListItem
                else -> named ?: BlockStyle.Paragraph
            }
            // Überschriften sind ohnehin hervorgehoben; ihr Fettdruck aus der Vorlage zählt nicht.
            val plain = style.isHeading
            paragraphs.addLast(
                Paragraph(
                    style,
                    level = if (style == BlockStyle.ListItem) listDepth - 1 else 0,
                    bold = !plain && styles.bold(styles.paragraph, styleName) == true,
                    italic = !plain && styles.italic(styles.paragraph, styleName) == true,
                ),
            )
        }

        private fun text(paragraph: Paragraph, raw: String) {
            val out = StringBuilder(raw.length)
            for (c in raw) {
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    if (!paragraph.afterSpace) out.append(' ')
                    paragraph.afterSpace = true
                } else {
                    out.append(c)
                    paragraph.afterSpace = false
                }
            }
            val (bold, italic) = paragraph.formats.last()
            builder.append(out.toString(), bold, italic)
            if (out.isNotBlank()) paragraph.hasText = true
        }

        /** Ausdrückliche Leerzeichen, Tabulatoren und Umbrüche (text:s, text:tab, text:line-break). */
        private fun append(paragraph: Paragraph, text: String) {
            val (bold, italic) = paragraph.formats.last()
            builder.append(text, bold, italic)
            paragraph.afterSpace = false
        }

        private fun emit(paragraph: Paragraph) {
            val item = paragraph.style == BlockStyle.ListItem
            val marker = if (item && paragraph.hasText && pendingMarkers.lastOrNull() == true) {
                pendingMarkers[pendingMarkers.lastIndex] = false
                "•"
            } else {
                null
            }
            builder.endBlock(paragraph.style, marker, paragraph.level)
            paragraph.hasText = false
        }
    }
}
