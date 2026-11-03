package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.TextDocument

/**
 * Ordnet Zeichenpositionen im [plainText] den Absätzen und Sätzen von [document] zu. Positionen
 * außerhalb des Textes werden auf Anfang bzw. Ende begrenzt. Sätze werden erst bei Bedarf und je
 * Absatz einmal bestimmt, damit auch lange Bücher (300 000 Zeichen) sofort angezeigt werden.
 */
class TextLayout(val document: TextDocument) {
    private val texts: List<String> = document.blocks.map { it.text }

    /** Wie [TextDocument.plainText], aber nur einmal zusammengesetzt. */
    val plainText: String = texts.joinToString(SEPARATOR)

    val length: Int get() = plainText.length

    /** Position des ersten Zeichens jedes Absatzes im [plainText]. */
    val blockStarts: IntArray = IntArray(texts.size).also { starts ->
        var position = 0
        texts.forEachIndexed { index, text ->
            starts[index] = position
            position += text.length + SEPARATOR.length
        }
    }

    private val sentenceCache = arrayOfNulls<List<IntRange>>(texts.size)

    /**
     * Der Absatz, in dem [offset] liegt. Die Leerzeile hinter einem Absatz und sein Ende zählen zu
     * ihm. -1 nur, wenn das Dokument keine Absätze hat.
     */
    fun blockAt(offset: Int): Int {
        if (blockStarts.isEmpty()) return -1
        val position = offset.coerceIn(0, length)
        var low = 0
        var high = blockStarts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (blockStarts[mid] <= position) low = mid else high = mid - 1
        }
        return low
    }

    /** Die Sätze von Absatz [block], als Positionen im [plainText]. */
    fun sentences(block: Int): List<IntRange> = sentenceCache[block] ?: run {
        val start = blockStarts[block]
        Sentences.ranges(texts[block]).map { (it.first + start)..(it.last + start) }.also { sentenceCache[block] = it }
    }

    /**
     * Der Satz, der [offset] enthält; liegt [offset] im Leerraum davor, der nächste Satz im selben
     * Absatz. null hinter dem letzten Satz eines Absatzes.
     */
    fun sentenceAt(offset: Int): IntRange? {
        val block = blockAt(offset)
        if (block < 0) return null
        val position = offset.coerceIn(0, length)
        val sentences = sentences(block)
        // Der erste Satz, dessen letztes Zeichen bei oder hinter der Stelle liegt.
        var low = 0
        var high = sentences.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sentences[mid].last < position) low = mid + 1 else high = mid
        }
        return sentences.getOrNull(low)
    }

    internal companion object {
        /** Trennt im [plainText] die Absätze, wie in [TextDocument.plainText]. */
        const val SEPARATOR = "\n\n"
    }
}
