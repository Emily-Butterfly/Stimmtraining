package de.emilybutterfly.stimmtraining.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.data.library.TextLayout
import de.emilybutterfly.stimmtraining.data.library.TextProgress

/**
 * Lesefortschritt in der Leseansicht eines Bibliothekstexts.
 *
 * [start]: bis hier war schon gelesen. Davor wird ausgeblendet; nur der letzte gelesene Satz
 * (bzw. der Teil der Seite über der Linie) bleibt blass als Anschluss stehen. Mit [showAll] bleibt
 * alles sichtbar und das Gelesene ist nur blass, z. B. in der Bibliothek.
 * [end]: in dieser Sitzung gelesen bis hier, farbig hinterlegt. Ist [onMark] gesetzt, lässt sich
 * die Stelle antippen: ein Satz im Text oder eine Höhe auf der Seite.
 */
@Immutable
data class ReadingMarks(
    val start: ReadingPosition? = null,
    val end: ReadingPosition? = null,
    val onMark: ((ReadingPosition) -> Unit)? = null,
    val showAll: Boolean = false,
) {
    private val startPage: ReadingPosition.OnPage? get() = start as? ReadingPosition.OnPage
    private val endPage: ReadingPosition.OnPage? get() = end as? ReadingPosition.OnPage

    /** Die erste gezeigte Seite: beim Weiterlesen die, auf der zuletzt aufgehört wurde. */
    fun firstPage(pageCount: Int): Int =
        if (showAll || pageCount == 0) 0 else (startPage?.page ?: 0).coerceIn(0, pageCount - 1)

    /** Was auf Seite [page] zu sehen ist: Ausgegrautes, farbig Gelesenes und die Linie. */
    fun pageMarks(page: Int): PageMarks? {
        val from = startPage
        val to = endPage
        val dimAbove = when {
            from == null -> null
            page < from.page -> if (showAll) 1f else null
            page == from.page -> from.y.takeIf { it > 0f }
            else -> null
        }
        val fromPage = from?.page ?: 0
        val fromY = from?.y ?: 0f
        val read = to?.takeIf { page in fromPage..it.page }?.let {
            val top = if (page == fromPage) fromY else 0f
            val bottom = if (page == it.page) it.y else 1f
            top to bottom
        }
        val mark = to?.takeIf { it.page == page }?.y
        if (dimAbove == null && read == null && mark == null) return null
        return PageMarks(dimAbove = dimAbove, readFrom = read?.first, readTo = read?.second, mark = mark)
    }

    /**
     * Die Stelle „bis hier gelesen“ für einen Tipp auf Seite [page] in Höhe [y]; null für einen Tipp
     * auf schon früher Gelesenes.
     */
    fun tapPage(page: Int, y: Float, pageName: String?): ReadingPosition.OnPage? {
        val from = startPage
        if (!showAll && from != null && (page < from.page || (page == from.page && y <= from.y))) return null
        return ReadingPosition.OnPage(page, y.coerceIn(0f, 1f), pageName)
    }
}

/** Ein Absatz in der Leseansicht: Absatz [index] des Dokuments, gezeigt ab Zeichen [from]. */
@Immutable
class ShownBlock(val index: Int, val from: Int)

/** Was von einem Text zu sehen ist und wie, für [marks]. */
class TextReading(
    private val layout: TextLayout,
    private val marks: ReadingMarks,
    private val read: Color,
    private val faded: Color,
) {
    private val startOffset: Int = (marks.start as? ReadingPosition.InText)?.offset?.coerceIn(0, layout.length) ?: 0

    /** Ab hier blass: der letzte gelesene Satz, oder bei [ReadingMarks.showAll] alles Gelesene. */
    private val fadedFrom: Int = when {
        marks.start == null -> startOffset
        marks.showAll -> 0
        else -> TextProgress.contextSentence(layout, startOffset)?.first ?: startOffset
    }

    private val endOffset: Int? = (marks.end as? ReadingPosition.InText)?.offset?.coerceIn(0, layout.length)

    val blocks: List<ShownBlock> = run {
        val from = if (marks.showAll) 0 else fadedFrom
        if (layout.blockStarts.isEmpty()) return@run emptyList()
        val first = layout.blockAt(from)
        (first until layout.blockStarts.size).map { index ->
            ShownBlock(index, if (index == first) (from - layout.blockStarts[index]).coerceAtLeast(0) else 0)
        }
    }

    /** Der Absatz [shown] ([full] ist sein ganzer Text) mit Blassem und farbig Gelesenem. */
    fun annotated(shown: ShownBlock, full: AnnotatedString): AnnotatedString {
        val from = shown.from.coerceAtMost(full.length)
        val part = if (from == 0) full else full.subSequence(from, full.length)
        val base = layout.blockStarts[shown.index] + from
        val builder = AnnotatedString.Builder(part)
        fun style(start: Int, end: Int, style: SpanStyle) {
            val a = (start - base).coerceIn(0, part.length)
            val b = (end - base).coerceIn(0, part.length)
            if (b > a) builder.addStyle(style, a, b)
        }
        style(fadedFrom, startOffset, SpanStyle(color = faded))
        endOffset?.let { style(startOffset, it, SpanStyle(background = read)) }
        return builder.toAnnotatedString()
    }

    /** Ein Tipp auf Zeichen [local] von [shown]: der Satz dort ist der zuletzt gelesene. */
    fun tap(shown: ShownBlock, local: Int) {
        val onMark = marks.onMark ?: return
        val blockStart = layout.blockStarts[shown.index]
        var offset = blockStart + shown.from + local
        // Hinter dem Zeilenende oder auf Leerraum getippt: gemeint ist der Satz davor, nicht der nächste.
        val text = layout.plainText
        if (offset > blockStart && (offset >= text.length || text[offset].isWhitespace())) offset--
        // Schon früher Gelesenes (blass) zählt nicht.
        if (!marks.showAll && marks.start != null && offset < startOffset) return
        val sentence = layout.sentenceAt(offset) ?: return
        onMark(TextProgress.after(layout, sentence))
    }
}

@Composable
fun rememberTextReading(document: TextDocument, marks: ReadingMarks): TextReading {
    val layout = remember(document) { TextLayout(document) }
    val scheme = MaterialTheme.colorScheme
    val read = scheme.primary.copy(alpha = 0.18f)
    val faded = scheme.onSurface.copy(alpha = 0.4f)
    return remember(layout, marks, read, faded) { TextReading(layout, marks, read, faded) }
}
