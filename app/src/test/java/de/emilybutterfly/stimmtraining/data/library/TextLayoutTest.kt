package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.BlockStyle
import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.document.TextSpan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TextLayoutTest {
    private val document = TextDocument(
        listOf(
            TextBlock(listOf(TextSpan("Erster Absatz. "), TextSpan("Zwei Sätze.", bold = true))),
            TextBlock(listOf(TextSpan("Überschrift")), BlockStyle.Heading1),
            TextBlock(listOf(TextSpan("Zeile eins\nZeile zwei.")), BlockStyle.ListItem, marker = "•"),
        ),
    )
    private val layout = TextLayout(document)
    private val text = document.plainText

    private fun sentence(range: IntRange?): String? = range?.let { text.substring(it) }

    @Test
    fun blockStartsAndLength() {
        assertEquals(text, layout.plainText)
        assertEquals(text.length, layout.length)
        assertArrayEquals(intArrayOf(0, 28, 41), layout.blockStarts)
        document.blocks.forEachIndexed { index, block ->
            assertEquals(block.text, text.substring(layout.blockStarts[index], layout.blockStarts[index] + block.text.length))
        }
    }

    @Test
    fun blockAtMapsGapsAndEndsToTheBlockBefore() {
        assertEquals(0, layout.blockAt(0))
        assertEquals(0, layout.blockAt(25))
        assertEquals(0, layout.blockAt(26)) // Ende des Absatzes
        assertEquals(0, layout.blockAt(27)) // Leerzeile
        assertEquals(1, layout.blockAt(28))
        assertEquals(1, layout.blockAt(40))
        assertEquals(2, layout.blockAt(41))
        assertEquals(2, layout.blockAt(layout.length))
        // Begrenzt
        assertEquals(0, layout.blockAt(-5))
        assertEquals(2, layout.blockAt(10_000))
        assertEquals(-1, TextLayout(TextDocument(emptyList())).blockAt(0))
    }

    @Test
    fun sentencesInGlobalPositions() {
        assertEquals(listOf("Erster Absatz.", "Zwei Sätze."), layout.sentences(0).map { text.substring(it) })
        assertEquals(listOf(0..13, 15..25), layout.sentences(0))
        assertEquals(listOf(28..38), layout.sentences(1))
        assertEquals(listOf("Zeile eins", "Zeile zwei."), layout.sentences(2).map { text.substring(it) })
        assertSame("Sätze werden je Absatz nur einmal bestimmt", layout.sentences(2), layout.sentences(2))
    }

    @Test
    fun sentenceAtFindsTheTappedOrFollowingSentence() {
        assertEquals("Erster Absatz.", sentence(layout.sentenceAt(0)))
        assertEquals("Erster Absatz.", sentence(layout.sentenceAt(13)))
        // Leerzeichen zwischen den Sätzen: der folgende
        assertEquals("Zwei Sätze.", sentence(layout.sentenceAt(14)))
        assertEquals("Zwei Sätze.", sentence(layout.sentenceAt(25)))
        // Hinter dem letzten Satz eines Absatzes
        assertNull(layout.sentenceAt(26))
        assertNull(layout.sentenceAt(27))
        assertEquals("Überschrift", sentence(layout.sentenceAt(28)))
        // Zeilenumbruch: der Satz der nächsten Zeile
        val lineBreak = text.indexOf('\n', 41)
        assertEquals("Zeile zwei.", sentence(layout.sentenceAt(lineBreak)))
        assertEquals("Zeile zwei.", sentence(layout.sentenceAt(layout.length - 1)))
        assertNull(layout.sentenceAt(layout.length))
        assertEquals("Erster Absatz.", sentence(layout.sentenceAt(-3)))
        assertNull(TextLayout(TextDocument(emptyList())).sentenceAt(0))
    }

    @Test
    fun blankAndEmptyBlocksHaveNoSentences() {
        val odd = TextLayout(TextDocument(listOf(TextBlock(emptyList()), TextBlock(listOf(TextSpan("  "))), TextBlock(listOf(TextSpan("Text."))))))
        assertArrayEquals(intArrayOf(0, 2, 6), odd.blockStarts)
        assertEquals(emptyList<IntRange>(), odd.sentences(0))
        assertEquals(emptyList<IntRange>(), odd.sentences(1))
        assertEquals(listOf(6..10), odd.sentences(2))
        assertNull(odd.sentenceAt(3))
        assertEquals(6..10, odd.sentenceAt(6))
    }
}
