package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.document.TextSpan
import kotlin.math.abs

/**
 * Lesefortschritt in einem Text: Man tippt nach der Aufnahme den zuletzt gelesenen Satz an, beim
 * nächsten Mal wird nur der Rest gezeigt (mit diesem Satz blass darüber), und die Aufnahme behält
 * nur den gelesenen [Abschnitt][section]. Alle Positionen beziehen sich auf den
 * [plainText][TextDocument.plainText].
 */
object TextProgress {
    /** Längere Sätze werden nur mit ihrem Ende gespeichert; das genügt, um sie wiederzufinden. */
    const val MAX_ANCHOR = 300

    /** Gelesen bis einschließlich [sentence] (z. B. aus [TextLayout.sentenceAt]). */
    fun after(layout: TextLayout, sentence: IntRange): ReadingPosition.InText {
        val text = layout.plainText
        var end = (sentence.last + 1).coerceIn(0, text.length)
        var start = sentence.first.coerceIn(0, end)
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end - start > MAX_ANCHOR) {
            start = end - MAX_ANCHOR
            if (text[start].isLowSurrogate()) start++
        }
        while (start < end && text[start].isWhitespace()) start++
        return ReadingPosition.InText(end, text.substring(start, end))
    }

    /**
     * Findet [position] in einem (womöglich geänderten) Text wieder: Steht der Anker noch genau vor
     * der Stelle, bleibt sie; sonst gilt das Vorkommen des Ankers, das der alten Stelle am nächsten
     * endet. null, wenn der Satz nicht mehr vorkommt – dann beginnt man von vorne.
     */
    fun resolve(document: TextDocument, position: ReadingPosition.InText): ReadingPosition.InText? =
        resolve(document.plainText, position)

    fun resolve(layout: TextLayout, position: ReadingPosition.InText): ReadingPosition.InText? =
        resolve(layout.plainText, position)

    private fun resolve(text: String, position: ReadingPosition.InText): ReadingPosition.InText? {
        val anchor = position.anchor
        if (anchor.isBlank()) return null
        val offset = position.offset
        if (offset in anchor.length..text.length && text.startsWith(anchor, offset - anchor.length)) return position
        var best = -1
        var bestDistance = Int.MAX_VALUE
        var index = text.indexOf(anchor)
        while (index >= 0) {
            val end = index + anchor.length
            val distance = abs(end - offset)
            // Bei gleichem Abstand gilt das frühere Vorkommen: lieber etwas doppelt lesen als etwas auslassen.
            if (distance < bestDistance) {
                best = end
                bestDistance = distance
            } else if (end > offset) {
                break
            }
            index = text.indexOf(anchor, index + 1)
        }
        return if (best < 0) null else ReadingPosition.InText(best, anchor)
    }

    /** Wo der ungelesene Rest ab [offset] beginnt: das erste sichtbare Zeichen ([TextLayout.length], wenn keins mehr kommt). */
    fun remainingStart(layout: TextLayout, offset: Int): Int = remainingStart(layout.plainText, offset)

    fun remainingStart(document: TextDocument, offset: Int): Int = remainingStart(document.plainText, offset)

    private fun remainingStart(text: String, offset: Int): Int {
        var position = offset.coerceIn(0, text.length)
        while (position < text.length && text[position].isWhitespace()) position++
        return position
    }

    /** true, wenn hinter [offset] nur noch Leerraum steht. */
    fun isAtEnd(layout: TextLayout, offset: Int): Boolean = remainingStart(layout, offset) >= layout.length

    /**
     * Der zuletzt gelesene Satz vor [offset], der beim Weiterlesen blass über dem Rest steht: der
     * letzte Satz, der bei oder vor [offset] endet. Liegt [offset] mitten in einem Satz, ist es dessen
     * schon gelesener Anfang, damit zwischen blassem Satz und Rest nichts fehlt. null am Textanfang.
     */
    fun contextSentence(layout: TextLayout, offset: Int): IntRange? {
        val position = offset.coerceIn(0, layout.length)
        if (position == 0) return null
        var block = layout.blockAt(position - 1)
        while (block >= 0) {
            val sentences = layout.sentences(block)
            val sentence = sentences.getOrNull(countStartingBefore(sentences, position) - 1)
            if (sentence != null) {
                if (sentence.last < position) return sentence
                var end = position
                while (layout.plainText[end - 1].isWhitespace()) end--
                return sentence.first until end
            }
            block--
        }
        return null
    }

    /** Wie viele der aufsteigenden [sentences] vor [position] beginnen. */
    private fun countStartingBefore(sentences: List<IntRange>, position: Int): Int {
        var low = 0
        var high = sentences.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sentences[mid].first < position) low = mid + 1 else high = mid
        }
        return low
    }

    /** Anteil des gelesenen Textes, 0 bis 1; 1, sobald nur noch Leerraum folgt. */
    fun fraction(layout: TextLayout, offset: Int): Float {
        if (isAtEnd(layout, offset)) return 1f
        return offset.coerceIn(0, layout.length).toFloat() / layout.length
    }

    /**
     * Der Teil `[start, end)` des Dokuments als eigenes Dokument, z. B. der in einer Aufnahme gelesene
     * Abschnitt. Angeschnittene Absätze behalten Art, Aufzählungszeichen, Ebene sowie fett und kursiv;
     * Leerraum an den Rändern fällt wie beim Einlesen weg, leere Absätze entfallen.
     */
    fun section(document: TextDocument, start: Int, end: Int): TextDocument {
        val blocks = ArrayList<TextBlock>()
        var blockStart = 0
        for (block in document.blocks) {
            if (blockStart >= end) break
            val length = block.spans.sumOf { it.text.length }
            val from = maxOf(start, blockStart) - blockStart
            val to = minOf(end, blockStart + length) - blockStart
            if (from < to) cut(block, from, to)?.let { blocks += it }
            blockStart += length + TextLayout.SEPARATOR.length
        }
        return TextDocument(blocks, truncated = false)
    }

    /** Die Zeichen [from] bis [to] von [block], getrimmt; null, wenn nichts Sichtbares bleibt. */
    private fun cut(block: TextBlock, from: Int, to: Int): TextBlock? {
        val spans = ArrayList<TextSpan>()
        var position = 0
        for (span in block.spans) {
            val spanEnd = position + span.text.length
            val a = maxOf(from, position)
            val b = minOf(to, spanEnd)
            if (a < b) spans += if (a == position && b == spanEnd) span else span.copy(text = span.text.substring(a - position, b - position))
            position = spanEnd
            if (position >= to) break
        }
        val trimmed = trim(spans)
        return if (trimmed.isEmpty()) null else block.copy(spans = trimmed)
    }

    /** Wie beim Einlesen ([de.emilybutterfly.stimmtraining.data.document.TextDocumentBuilder]): kein Leerraum am Anfang und Ende. */
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
