package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.BlockStyle
import de.emilybutterfly.stimmtraining.data.document.MarkdownReader
import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.document.TextDocumentBuilder

/**
 * Schreibt ein [TextDocument] als Markdown, das [MarkdownReader] als dasselbe Dokument zurückliest:
 * gleiche Absätze und Texte, Absatzarten, Aufzählungszeichen, Ebenen, fett und kursiv. So behält
 * eine Aufnahme den gelesenen Abschnitt, und die Datei bleibt auch in anderen Programmen lesbar.
 *
 * Alles, was Markdown deuten könnte, wird mit Backslash geschützt, Leerraum am Zeilenanfang als
 * Entität (`&#32;`), denn Leser entfernen ihn. Zeilenumbrüche im Absatz werden harte Umbrüche
 * (`\` am Zeilenende), in Überschriften `<br>`.
 *
 * Was sich in Markdown nicht ausdrücken lässt:
 * - Ein Listenpunkt steht höchstens eine Ebene tiefer als der vorige, der erste einer Liste auf
 *   Ebene 0. Aufzählungszeichen außer „1.“, „2.“ … werden zu „•“.
 * - Ein Listenabsatz ohne Aufzählungszeichen und ohne Listenpunkt davor wird ein normaler Absatz.
 * - Fett/kursiv auf Leerraum am Rand einer Hervorhebung entfällt (`**fett** ` statt `**fett **`).
 * - Absätze mit Zeilenumbrüchen teilt der Leser hinter [TextDocumentBuilder.LONG_BLOCK_CHARS]
 *   Zeichen an einem Umbruch, wie beim ersten Einlesen.
 * - Das Steuerzeichen U+0085 in Überschriften und der ersten Zeile eines Listenpunkts.
 */
object MarkdownWriter {
    private const val BOLD = 1
    private const val ITALIC = 2

    /** Leerer HTML-Kommentar: trennt ein Hervorhebungszeichen von seinem Nachbarn, ohne Text zu erzeugen. */
    private const val SEPARATOR = "<!---->"

    /** Überall bedeutsam: Escape, Code, Hervorhebung, Links, HTML, Überschriften-Rauten, Tabellen, Durchstreichen. */
    private const val ESCAPED = "\\`*_[]<#|~"

    /** Am Zeilenanfang bedeutsam: Listen, Trennlinien, Setext-Überschriften, Zitate. */
    private const val ESCAPED_AT_LINE_START = "+-=>"

    private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val ORDERED_MARKER = Regex("""\d{1,9}\.""")

    // Wie ein Zeichen geschrieben wird.
    private const val LITERAL = 0
    private const val ESCAPE = 1
    private const val ENTITY = 2
    private const val LINE_BREAK = 3

    /** Was vor der ersten und vor jeder weiteren Zeile eines Absatzes steht. */
    private class Prefix(val first: String, val next: String, val heading: Boolean = false)

    fun write(document: TextDocument): String {
        val out = StringBuilder()
        // Spalte, in der der Text der offenen Listenebenen beginnt; MarkdownReader verfolgt dieselben Ebenen.
        val listColumns = ArrayList<Int>()
        for (block in document.blocks) {
            val text = block.text
            if (text.isBlank()) continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(block(block, text, listColumns))
        }
        if (out.isNotEmpty()) out.append('\n')
        return out.toString()
    }

    private fun block(block: TextBlock, text: String, listColumns: MutableList<Int>): String {
        val prefix = prefix(block, listColumns)
        if (block.spans.any { (it.bold || it.italic) && it.text.isNotBlank() }) {
            val markdown = lines(prefix, inline(text, prefix.heading, flags(block, text)))
            // Die Hervorhebungsregeln sind verwickelt (und der Leser verkraftet nur begrenzt viele
            // Zeichen je Absatz). Liest er den Absatz nicht genau so zurück, bleibt er ohne Hervorhebung –
            // so geht nie Text verloren oder kommen Sternchen hinzu.
            if (readsBack(markdown, block)) return markdown
        }
        return lines(prefix, inline(text, prefix.heading, null))
    }

    private fun prefix(block: TextBlock, listColumns: MutableList<Int>): Prefix {
        if (block.style != BlockStyle.ListItem) listColumns.clear()
        val level = block.level.coerceAtLeast(0)
        val marker = block.marker?.takeIf { it.isNotBlank() }
        return when (block.style) {
            BlockStyle.Heading1 -> Prefix("# ", "", heading = true)
            BlockStyle.Heading2 -> Prefix("## ", "", heading = true)
            BlockStyle.Heading3 -> Prefix("### ", "", heading = true)
            BlockStyle.Quote -> Prefix("> ", "> ")
            BlockStyle.Paragraph -> Prefix("", "")
            BlockStyle.ListItem -> when {
                marker != null -> {
                    // Unterpunkte beginnen dort, wo der Text des übergeordneten Punkts beginnt (wie in CommonMark).
                    val itemLevel = minOf(level, listColumns.size)
                    val indent = if (itemLevel == 0) 0 else listColumns[itemLevel - 1]
                    val mark = if (ORDERED_MARKER.matches(marker)) marker else "-"
                    while (listColumns.size > itemLevel) listColumns.removeAt(listColumns.lastIndex)
                    listColumns += indent + mark.length + 1
                    Prefix(" ".repeat(indent) + mark + " ", " ".repeat(listColumns.last()))
                }
                // Weiterer Absatz eines Listenpunkts: eingerückt wie dessen Text.
                listColumns.isNotEmpty() -> " ".repeat(listColumns[minOf(level, listColumns.lastIndex)]).let { Prefix(it, it) }
                else -> Prefix("", "")
            }
        }
    }

    private fun lines(prefix: Prefix, content: String): String {
        val out = StringBuilder(content.length + 16).append(prefix.first)
        for (c in content) {
            out.append(c)
            if (c == '\n') out.append(prefix.next)
        }
        return out.toString()
    }

    /**
     * Der Text eines Absatzes als Markdown ohne Zeilenpräfixe, mit Hervorhebung nach [flags]
     * (je Zeichen [BOLD]/[ITALIC]; null = ohne). Genau diesen Text sieht MarkdownReader, nachdem er
     * Präfixe und Leerraum der Zeilen entfernt hat; danach richten sich die Hervorhebungszeichen.
     */
    private fun inline(text: String, heading: Boolean, flags: IntArray?): String {
        val out = StringBuilder(text.length + text.length / 8 + 16)
        val open = ArrayList<Int>(2)
        // Punkt oder Klammer hinter einer Zahl am Zeilenanfang („1. “ wäre ein Listenpunkt).
        var escapedMarker = -1
        for (i in 0..text.length) {
            val inText = i < text.length
            val lineStart = inText && (i == 0 || !heading && text[i - 1] == '\n')
            if (lineStart) escapedMarker = orderedMarkerEnd(text, i)
            val kind = if (inText) kind(text, i, lineStart, escapedMarker) else LITERAL
            if (flags != null) {
                val previous = if (i > 0) flags[i - 1] else 0
                val next = if (inText) flags[i] else 0
                if (previous != next) emphasis(out, open, previous, next, if (inText) firstChar(kind, text[i], heading) else ' ')
            }
            if (inText) append(out, kind, text[i], heading)
        }
        return out.toString()
    }

    private fun kind(text: String, i: Int, lineStart: Boolean, escapedMarker: Int): Int {
        val c = text[i]
        return when {
            c == '\n' -> LINE_BREAK
            // Steuerzeichen entfernt das Einlesen der Datei (aus '\r' und Seitenvorschub werden Zeilen),
            // Zeilentrenner beenden Zeilen, Leerraum am Zeilenanfang fiele weg.
            c < ' ' && c != '\t' || c == '\u007F' || c == '\u2028' || c == '\u2029' || c == '\uFEFF' ||
                lineStart && c.isWhitespace() -> ENTITY
            c in ESCAPED || lineStart && c in ESCAPED_AT_LINE_START || i == escapedMarker -> ESCAPE
            // "&amp;" & Co. würden entschlüsselt.
            c == '&' && text.getOrNull(i + 1).let { it == '#' || it != null && (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') } -> ESCAPE
            else -> LITERAL
        }
    }

    /** Position des '.' oder ')' in „12. “ am Zeilenanfang [start], sonst -1. */
    private fun orderedMarkerEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] in '0'..'9') end++
        if (end == start || end >= text.length || (text[end] != '.' && text[end] != ')')) return -1
        val after = text.getOrNull(end + 1)
        return if (after == null || after == ' ' || after == '\t' || after == '\n') end else -1
    }

    private fun append(out: StringBuilder, kind: Int, c: Char, heading: Boolean) {
        when (kind) {
            LITERAL -> out.append(c)
            ESCAPE -> out.append('\\').append(c)
            ENTITY -> out.append("&#").append(c.code).append(';')
            else -> out.append(if (heading) "<br>" else "\\\n")
        }
    }

    /** Das erste Zeichen, das [append] schreibt – der Nachbar, nach dem sich Hervorhebungszeichen richten. */
    private fun firstChar(kind: Int, c: Char, heading: Boolean): Char = when (kind) {
        LITERAL -> c
        ESCAPE -> '\\'
        ENTITY -> '&'
        else -> if (heading) '<' else '\\'
    }

    /**
     * Fett/kursiv je Zeichen. Leerraum ist nur hervorgehoben, wenn er es selbst ist und die sichtbaren
     * Zeichen links und rechts auch: Eine Hervorhebung beginnt und endet so immer an einem sichtbaren
     * Zeichen, sonst erkennt Markdown die Zeichen nicht.
     */
    private fun flags(block: TextBlock, text: String): IntArray {
        val flags = IntArray(text.length)
        var position = 0
        for (span in block.spans) {
            val value = (if (span.bold) BOLD else 0) or (if (span.italic) ITALIC else 0)
            flags.fill(value, position, position + span.text.length)
            position += span.text.length
        }
        var i = 0
        while (i < text.length) {
            if (!text[i].isWhitespace()) {
                i++
                continue
            }
            var end = i
            var value = BOLD or ITALIC
            while (end < text.length && text[end].isWhitespace()) value = value and flags[end++]
            val left = if (i > 0) flags[i - 1] else 0
            val right = if (end < text.length) flags[end] else 0
            flags.fill(value and left and right, i, end)
            i = end
        }
        return flags
    }

    /**
     * Schreibt die Hervorhebungszeichen für den Wechsel von [previous] zu [next] vor das Zeichen, das
     * mit [after] beginnt. Geschlossen wird in umgekehrter Reihenfolge des Öffnens ([open]); was dabei
     * zu früh endet, wird gleich wieder geöffnet. Würde Markdown ein Zeichen wegen seiner Nachbarn
     * nicht als Beginn oder Ende erkennen (`Wort*(kursiv)*`, `*un*möglich`), trennt es ein leerer Kommentar.
     */
    private fun emphasis(out: StringBuilder, open: MutableList<Int>, previous: Int, next: Int, after: Char) {
        val ending = previous and next.inv()
        val closers = StringBuilder()
        val reopened = ArrayList<Int>(2)
        while (open.any { (it and ending) != 0 }) {
            val attribute = open.removeAt(open.lastIndex)
            closers.append(mark(attribute))
            if ((attribute and ending) == 0) reopened.add(0, attribute)
        }
        val starting = next and previous.inv()
        val openers = StringBuilder()
        for (attribute in reopened + listOf(BOLD, ITALIC).filter { (starting and it) != 0 }) {
            open += attribute
            openers.append(mark(attribute))
        }
        if (closers.isNotEmpty()) {
            val before = out.lastOrNull() ?: ' '
            out.append(closers)
            // Direkt aufeinander folgende schließende und öffnende Zeichen wären eine einzige Folge.
            if (openers.isNotEmpty() || !canClose(before, closers.length, after)) out.append(SEPARATOR)
        }
        if (openers.isNotEmpty()) {
            if (!canOpen(out.lastOrNull() ?: ' ', openers.length, after)) out.append(SEPARATOR)
            out.append(openers)
        }
    }

    private fun mark(attribute: Int): String = if (attribute == BOLD) "**" else "*"

    // Die Regeln von MarkdownReader (CommonMark samt Ausnahme für den Genderstern) für eine Folge aus '*'.
    private fun canOpen(before: Char, length: Int, after: Char): Boolean =
        !after.isWhitespace() && (!after.isPunctuation() || before.isWhitespace() || before.isPunctuation()) &&
            !isGenderStar(before, length, after)

    private fun canClose(before: Char, length: Int, after: Char): Boolean =
        !before.isWhitespace() && (!before.isPunctuation() || after.isWhitespace() || after.isPunctuation()) &&
            !isGenderStar(before, length, after)

    private fun isGenderStar(before: Char, length: Int, after: Char): Boolean =
        length == 1 && before.isLetterOrDigit() && after.isLetterOrDigit()

    private fun Char.isPunctuation(): Boolean = this in ASCII_PUNCTUATION || when (Character.getType(this).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }

    /** Ob MarkdownReader [markdown] mit denselben sichtbaren Zeichen und derselben Hervorhebung liest wie [block]. */
    private fun readsBack(markdown: String, block: TextBlock): Boolean {
        val builder = TextDocumentBuilder(Int.MAX_VALUE)
        MarkdownReader.read(markdown, builder)
        return visible(listOf(block)) == visible(builder.build().blocks)
    }

    /** Die sichtbaren Zeichen, jedes gefolgt von einer Ziffer für seine Hervorhebung. */
    private fun visible(blocks: List<TextBlock>): String = buildString {
        for (block in blocks) {
            for (span in block.spans) {
                val flag = '0' + ((if (span.bold) BOLD else 0) or (if (span.italic) ITALIC else 0))
                for (c in span.text) if (!c.isWhitespace()) append(c).append(flag)
            }
        }
    }
}
