package de.emilybutterfly.stimmtraining.data.document

/**
 * Lesbarer Text eines Dokuments, unabhängig vom Dateiformat: eine Folge von Absätzen mit
 * einfacher Auszeichnung (Überschrift, Listenpunkt, fett, kursiv). Mehr braucht es nicht,
 * um einen Text beim Aufnehmen abzulesen.
 *
 * [truncated] ist gesetzt, wenn das Dokument länger als erlaubt war und gekürzt wurde.
 */
data class TextDocument(val blocks: List<TextBlock>, val truncated: Boolean = false) {
    val plainText: String get() = blocks.joinToString("\n\n") { it.text }

    fun isBlank(): Boolean = blocks.all { it.text.isBlank() }
}

enum class BlockStyle { Paragraph, Heading1, Heading2, Heading3, ListItem, Quote }

/**
 * Ein Absatz. Zeilenumbrüche innerhalb des Absatzes stehen als '\n' im Text.
 * [marker] ist bei Listenpunkten das Aufzählungszeichen („•“, „1.“), [level] die
 * Einrückungsebene ab 0.
 */
data class TextBlock(
    val spans: List<TextSpan>,
    val style: BlockStyle = BlockStyle.Paragraph,
    val marker: String? = null,
    val level: Int = 0,
) {
    val text: String get() = spans.joinToString("") { it.text }
}

data class TextSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false)

/**
 * Baut ein [TextDocument] Absatz für Absatz auf. Text wird mit [append] gesammelt und mit
 * [endBlock] zu einem Absatz abgeschlossen; Absätze ohne sichtbaren Text entfallen.
 * Nach [maxChars] Zeichen wird nichts mehr aufgenommen ([isFull]), damit auch riesige
 * Dateien die App nicht lahmlegen.
 */
class TextDocumentBuilder(private val maxChars: Int = DEFAULT_MAX_CHARS) {
    private val blocks = mutableListOf<TextBlock>()
    private val spans = mutableListOf<TextSpan>()
    private val current = StringBuilder()
    private var currentBold = false
    private var currentItalic = false
    private var chars = 0

    /** true, sobald die Höchstmenge erreicht ist; Leser können dann aufhören. */
    var isFull: Boolean = false
        private set

    fun append(text: String, bold: Boolean = false, italic: Boolean = false) {
        if (text.isEmpty() || isFull) return
        if (current.isNotEmpty() && (bold != currentBold || italic != currentItalic)) flushSpan()
        currentBold = bold
        currentItalic = italic
        val remaining = maxChars - chars
        if (text.length >= remaining) {
            current.append(text, 0, remaining)
            chars = maxChars
            isFull = true
        } else {
            current.append(text)
            chars += text.length
        }
    }

    fun append(char: Char, bold: Boolean = false, italic: Boolean = false) = append(char.toString(), bold, italic)

    /** Zeilenumbruch innerhalb des Absatzes. */
    fun lineBreak() = append("\n", currentBold, currentItalic)

    /** Schließt den aktuellen Absatz ab. */
    fun endBlock(style: BlockStyle = BlockStyle.Paragraph, marker: String? = null, level: Int = 0) {
        flushSpan()
        val trimmed = trim(spans)
        spans.clear()
        if (trimmed.isNotEmpty()) blocks += TextBlock(trimmed, style, marker, level.coerceIn(0, MAX_LEVEL))
    }

    fun build(): TextDocument {
        endBlock()
        return TextDocument(blocks.toList(), truncated = isFull)
    }

    private fun flushSpan() {
        if (current.isEmpty()) return
        spans += TextSpan(current.toString(), currentBold, currentItalic)
        current.setLength(0)
    }

    companion object {
        /** Etwa 150 Buchseiten – deutlich mehr, als man am Stück vorliest. */
        const val DEFAULT_MAX_CHARS = 300_000
        private const val MAX_LEVEL = 8

        /** Entfernt Leerraum am Anfang und Ende des Absatzes; nur Leerraum ergibt eine leere Liste. */
        private fun trim(spans: List<TextSpan>): List<TextSpan> {
            val first = spans.indexOfFirst { it.text.isNotBlank() }
            if (first < 0) return emptyList()
            val last = spans.indexOfLast { it.text.isNotBlank() }
            return spans.subList(first, last + 1).mapIndexedNotNull { index, span ->
                var text = span.text
                if (index == 0) text = text.trimStart()
                if (index == last - first) text = text.trimEnd()
                if (text.isEmpty()) null else span.copy(text = text)
            }
        }
    }
}
