package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.BlockStyle
import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.document.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProgressTest {
    private fun paragraph(text: String) = TextBlock(listOf(TextSpan(text)))
    private fun document(vararg texts: String) = TextDocument(texts.map(::paragraph))

    private val story = document(
        "Einst stritten sich Nordwind und Sonne. Wer von ihnen beiden wohl der Stärkere wäre.",
        "Da kam ein Wanderer. Er war in einen warmen Mantel gehüllt.",
        "Sie wurden einig.",
    )
    private val layout = TextLayout(story)
    private val text = layout.plainText

    /** Stelle hinter dem Satz, der [words] enthält – wie nach dem Antippen. */
    private fun tap(layout: TextLayout, words: String): ReadingPosition.InText =
        TextProgress.after(layout, layout.sentenceAt(layout.plainText.indexOf(words))!!)

    @Test
    fun tappingASentenceMarksItAsRead() {
        val sentence = layout.sentenceAt(text.indexOf("Stärkere"))!!
        val position = TextProgress.after(layout, sentence)
        assertEquals("Wer von ihnen beiden wohl der Stärkere wäre.", position.anchor)
        assertEquals(sentence.last + 1, position.offset)
        // Weiter geht es mit dem nächsten Absatz, der gelesene Satz steht blass darüber.
        assertEquals(layout.blockStarts[1], TextProgress.remainingStart(layout, position.offset))
        assertEquals(sentence, TextProgress.contextSentence(layout, position.offset))
        assertFalse(TextProgress.isAtEnd(layout, position.offset))
        assertEquals(position.offset.toFloat() / text.length, TextProgress.fraction(layout, position.offset), 1e-6f)
    }

    @Test
    fun lastSentenceMeansTheEnd() {
        val position = tap(layout, "einig")
        assertEquals(text.length, position.offset)
        assertTrue(TextProgress.isAtEnd(layout, position.offset))
        assertEquals(text.length, TextProgress.remainingStart(layout, position.offset))
        assertEquals(1f, TextProgress.fraction(layout, position.offset))
        // Nur Leerraum dahinter zählt auch als Ende.
        val trailing = TextLayout(TextDocument(listOf(TextBlock(listOf(TextSpan("Ende. "), TextSpan("  ", bold = true))))))
        assertTrue(TextProgress.isAtEnd(trailing, 5))
        assertEquals(1f, TextProgress.fraction(trailing, 5))
        val empty = TextLayout(TextDocument(emptyList()))
        assertTrue(TextProgress.isAtEnd(empty, 0))
        assertEquals(0, TextProgress.remainingStart(empty, 0))
        assertEquals(1f, TextProgress.fraction(empty, 0))
        assertEquals(0f, TextProgress.fraction(layout, 0))
        assertEquals(0f, TextProgress.fraction(layout, -4))
    }

    @Test
    fun anchorIsTrimmedAndLongSentencesKeepTheirEnd() {
        // Bis in die Leerzeile hinter dem Absatz
        val padded = TextProgress.after(layout, 40..text.indexOf("wäre.") + 6)
        assertEquals("Wer von ihnen beiden wohl der Stärkere wäre.", padded.anchor)
        assertEquals(text.indexOf("wäre.") + 5, padded.offset)

        val long = "Wort ".repeat(100) + "Ende."
        val longLayout = TextLayout(document(long))
        val position = TextProgress.after(longLayout, longLayout.sentences(0).single())
        assertEquals(long.length, position.offset)
        assertTrue(position.anchor.length <= TextProgress.MAX_ANCHOR)
        assertTrue(position.anchor.endsWith("Wort Ende."))
        assertFalse(position.anchor.first().isWhitespace())
        assertEquals(position.anchor, long.substring(position.offset - position.anchor.length, position.offset))
        assertSame(position, TextProgress.resolve(longLayout.document, position))

        // Kein halbes Emoji am Anfang des Ankers.
        val emoji = "😀".repeat(200) + "."
        val emojiLayout = TextLayout(document(emoji))
        val anchor = TextProgress.after(emojiLayout, emojiLayout.sentences(0).single()).anchor
        assertTrue(anchor.first().isHighSurrogate())
        assertTrue(anchor.endsWith("😀."))
    }

    @Test
    fun resolveKeepsAnUnchangedPosition() {
        val position = tap(layout, "Wanderer")
        assertSame(position, TextProgress.resolve(story, position))
        assertSame(position, TextProgress.resolve(layout, position))
        // Änderungen hinter der Stelle stören nicht.
        val edited = document(
            "Einst stritten sich Nordwind und Sonne. Wer von ihnen beiden wohl der Stärkere wäre.",
            "Da kam ein Wanderer. Er trug einen dicken Mantel.",
        )
        assertSame(position, TextProgress.resolve(edited, position))
    }

    @Test
    fun resolveFollowsTheSentenceWhenTextIsInsertedBefore() {
        val position = tap(layout, "Mantel")
        val edited = TextDocument(listOf(paragraph("Eine Fabel von Äsop.")) + story.blocks)
        val resolved = TextProgress.resolve(edited, position)!!
        assertEquals(position.offset + "Eine Fabel von Äsop.".length + 2, resolved.offset)
        assertEquals(position.anchor, resolved.anchor)
        assertEquals(position.anchor, edited.plainText.substring(resolved.offset - position.anchor.length, resolved.offset))
        // Gekürzt davor: rückt nach vorn.
        val shortened = document("Nordwind und Sonne stritten.", "Da kam ein Wanderer. Er war in einen warmen Mantel gehüllt.")
        assertEquals(shortened.plainText.length, TextProgress.resolve(shortened, position)!!.offset)
    }

    @Test
    fun resolveGivesUpWhenTheSentenceIsGone() {
        val position = tap(layout, "Mantel")
        assertNull(TextProgress.resolve(document("Da kam ein Wanderer. Er trug einen Mantel."), position))
        assertNull(TextProgress.resolve(TextDocument(emptyList()), position))
        assertNull(TextProgress.resolve(story, ReadingPosition.InText(10, "")))
        assertNull(TextProgress.resolve(story, ReadingPosition.InText(10, "   ")))
    }

    @Test
    fun resolvePicksTheNearestOfRepeatedSentences() {
        val chorus = document("Strophe eins.", "La la la.", "Strophe zwei.", "La la la.", "Strophe drei.", "La la la.")
        val chorusLayout = TextLayout(chorus)
        val second = TextProgress.after(chorusLayout, chorusLayout.sentences(3).single())
        assertSame(second, TextProgress.resolve(chorus, second))
        // Strophe zwei gekürzt: Der zweite Kehrreim rückt etwas nach vorn und bleibt der nächste.
        val edited = document("Strophe eins.", "La la la.", "Strophe 2.", "La la la.", "Strophe drei.", "La la la.")
        val resolved = TextProgress.resolve(edited, second)!!
        assertEquals(TextLayout(edited).sentences(3).single().last + 1, resolved.offset)
        assertEquals(second.anchor, resolved.anchor)
        // Ein Vorspiel am Anfang: Der zweite Kehrreim rückt nach hinten und bleibt der nächste.
        val longer = TextDocument(listOf(paragraph("Vorspiel.")) + chorus.blocks)
        assertEquals(TextLayout(longer).sentences(4).single().last + 1, TextProgress.resolve(longer, second)!!.offset)
        // Bei gleichem Abstand gilt das frühere Vorkommen: lieber doppelt lesen als etwas auslassen.
        assertEquals(2, TextProgress.resolve(document("ab  ab"), ReadingPosition.InText(4, "ab"))!!.offset)
        // Stelle außerhalb des Textes
        assertEquals(chorus.plainText.length, TextProgress.resolve(chorus, ReadingPosition.InText(99_999, "La la la."))!!.offset)
    }

    @Test
    fun contextSentenceIsTheLastReadOne() {
        assertNull(TextProgress.contextSentence(layout, 0))
        assertNull(TextProgress.contextSentence(layout, -3))
        val first = layout.sentences(0)[0]
        assertEquals(first, TextProgress.contextSentence(layout, first.last + 1))
        // Im Leerraum dahinter
        assertEquals(first, TextProgress.contextSentence(layout, first.last + 2))
        // Am Anfang des nächsten Absatzes: der letzte Satz davor
        assertEquals(layout.sentences(0)[1], TextProgress.contextSentence(layout, layout.blockStarts[1]))
        assertEquals(layout.sentences(0)[1], TextProgress.contextSentence(layout, layout.blockStarts[1] - 1))
        // Mitten im Satz: dessen schon gelesener Anfang, ohne Leerraum am Ende
        val middle = text.indexOf("Wanderer") - 1
        assertEquals("Da kam ein", text.substring(TextProgress.contextSentence(layout, middle)!!))
        assertEquals(layout.sentences(2).single(), TextProgress.contextSentence(layout, 10_000))
        // Leere Absätze werden übersprungen
        val gaps = TextLayout(TextDocument(listOf(paragraph("Eins."), TextBlock(emptyList()), paragraph("Zwei."))))
        assertEquals(0..4, TextProgress.contextSentence(gaps, gaps.blockStarts[2]))
    }

    @Test
    fun readingAcrossSeveralRecordings() {
        // Aufnahme 1: bis „Stärkere wäre.“
        val first = tap(layout, "Stärkere")
        val firstSection = TextProgress.section(story, 0, first.offset)
        assertEquals(listOf(story.blocks[0]), firstSection.blocks)

        // Aufnahme 2: Rest ab dem nächsten Absatz, der letzte Satz blass darüber.
        val resumed = TextProgress.resolve(story, first)!!
        val start = TextProgress.remainingStart(story, resumed.offset)
        assertEquals("Wer von ihnen beiden wohl der Stärkere wäre.", text.substring(TextProgress.contextSentence(layout, resumed.offset)!!))
        val second = tap(layout, "Wanderer")
        val secondSection = TextProgress.section(story, start, second.offset)
        assertEquals(listOf("Da kam ein Wanderer."), secondSection.blocks.map { it.text })

        // Aufnahme 3: bis zum Ende.
        val third = tap(layout, "einig")
        val thirdSection = TextProgress.section(story, TextProgress.remainingStart(layout, second.offset), third.offset)
        assertEquals(listOf("Er war in einen warmen Mantel gehüllt.", "Sie wurden einig."), thirdSection.blocks.map { it.text })
        assertTrue(TextProgress.isAtEnd(layout, third.offset))
    }

    private val formatted = TextDocument(
        listOf(
            TextBlock(listOf(TextSpan("Titel")), BlockStyle.Heading1),
            TextBlock(listOf(TextSpan("Ein "), TextSpan("fetter", bold = true), TextSpan(" Satz. Und "), TextSpan("noch einer", italic = true), TextSpan("."))),
            TextBlock(listOf(TextSpan("Punkt eins")), BlockStyle.ListItem, marker = "1.", level = 1),
            TextBlock(listOf(TextSpan("Punkt zwei")), BlockStyle.ListItem, marker = "•"),
        ),
        truncated = true,
    )
    private val formattedLayout = TextLayout(formatted)
    private val formattedText = formattedLayout.plainText

    @Test
    fun sectionOfTheWholeDocumentIsTheDocument() {
        assertEquals(TextDocument(formatted.blocks), TextProgress.section(formatted, 0, formattedText.length))
        assertEquals(TextDocument(formatted.blocks), TextProgress.section(formatted, -10, 10_000))
        assertFalse(TextProgress.section(formatted, 0, formattedText.length).truncated)
    }

    @Test
    fun sectionAtBlockBoundaries() {
        val starts = formattedLayout.blockStarts
        assertEquals(listOf(formatted.blocks[1]), TextProgress.section(formatted, starts[1], starts[2]).blocks)
        assertEquals(listOf(formatted.blocks[1]), TextProgress.section(formatted, starts[1] - 1, starts[2] - 1).blocks)
        assertEquals(formatted.blocks.subList(1, 3), TextProgress.section(formatted, starts[1], starts[2] + "Punkt eins".length).blocks)
        // Nur Leerzeile oder nichts
        assertEquals(emptyList<TextBlock>(), TextProgress.section(formatted, starts[1] - 2, starts[1]).blocks)
        assertEquals(emptyList<TextBlock>(), TextProgress.section(formatted, 20, 20).blocks)
        assertEquals(emptyList<TextBlock>(), TextProgress.section(formatted, 30, 10).blocks)
    }

    @Test
    fun sectionCutsSpansAndKeepsFormatting() {
        val start = formattedText.indexOf("tter")
        val end = formattedText.indexOf(" einer")
        val section = TextProgress.section(formatted, start, end)
        assertEquals(
            listOf(TextSpan("tter", bold = true), TextSpan(" Satz. Und "), TextSpan("noch", italic = true)),
            section.blocks.single().spans,
        )
        assertEquals(BlockStyle.Paragraph, section.blocks.single().style)
        // Satzgrenzen: Leerraum am Rand fällt weg, leere Spans entstehen nicht.
        val sentence = formattedLayout.sentences(1)[1]
        val cut = TextProgress.section(formatted, sentence.first - 1, sentence.last + 1)
        assertEquals(listOf(TextSpan("Und "), TextSpan("noch einer", italic = true), TextSpan(".")), cut.blocks.single().spans)
        val trimmed = TextProgress.section(formatted, formattedText.indexOf(" fetter"), formattedText.indexOf("fetter") + 7)
        assertEquals(listOf(TextSpan("fetter", bold = true)), trimmed.blocks.single().spans)
    }

    @Test
    fun cutListItemsKeepMarkerAndLevel() {
        val start = formattedText.indexOf("eins")
        val section = TextProgress.section(formatted, start, formattedText.length)
        assertEquals(
            listOf(
                TextBlock(listOf(TextSpan("eins")), BlockStyle.ListItem, marker = "1.", level = 1),
                TextBlock(listOf(TextSpan("Punkt zwei")), BlockStyle.ListItem, marker = "•"),
            ),
            section.blocks,
        )
        val heading = TextProgress.section(formatted, 2, 4)
        assertEquals(listOf(TextBlock(listOf(TextSpan("te")), BlockStyle.Heading1)), heading.blocks)
    }

    @Test
    fun sectionKeepsLineBreaksInside() {
        val poem = document("Erste Zeile\nZweite Zeile\nDritte Zeile")
        val poemLayout = TextLayout(poem)
        val lines = poemLayout.sentences(0)
        assertEquals(listOf("Zweite Zeile\nDritte Zeile"), TextProgress.section(poem, lines[0].last + 1, poemLayout.length).blocks.map { it.text })
        assertEquals(listOf("Erste Zeile\nZweite Zeile"), TextProgress.section(poem, 0, lines[1].last + 2).blocks.map { it.text })
    }
}
