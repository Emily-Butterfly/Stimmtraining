package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.MarkdownReader
import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.document.TextDocumentBuilder
import de.emilybutterfly.stimmtraining.data.document.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Ein Buch mit 300 000 Zeichen (die Höchstmenge beim Einlesen) muss sich ohne Wartezeit lesen lassen. */
class LibraryPerformanceTest {
    private val sentences = listOf(
        "Einst stritten sich Nordwind und Sonne, wer von ihnen beiden wohl der Stärkere wäre.",
        "Am 3. Oktober kam z. B. Dr. Müller vorbei.",
        "„Wirklich?“, fragte sie.",
        "Er zögerte … und dann ging er.",
        "Das war im Jahr 1990.",
    )

    private fun book(): TextDocument {
        val blocks = ArrayList<TextBlock>()
        var length = 0
        var i = 0
        while (length < TextDocumentBuilder.DEFAULT_MAX_CHARS) {
            val text = "Absatz $i: " + sentences.joinToString(" ")
            blocks += TextBlock(listOf(TextSpan("Absatz $i: ", bold = i % 3 == 0), TextSpan(sentences.joinToString(" "), italic = i % 5 == 0)))
            length += text.length + 2
            i++
        }
        return TextDocument(blocks)
    }

    private fun millis(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun measure(document: TextDocument): Long {
        val start = System.nanoTime()
        val layout = TextLayout(document)
        // Überall antippen: zerlegt nach und nach jeden Absatz in Sätze.
        val random = Random(1)
        repeat(20_000) { layout.sentenceAt(random.nextInt(layout.length)) }
        for (block in document.blocks.indices) layout.sentences(block)
        val last = TextProgress.after(layout, layout.sentenceAt(layout.length - 1)!!)
        // Text vorn eingefügt: Der (oft wiederholte) Satz muss im ganzen Text gesucht werden.
        val edited = TextDocument(listOf(TextBlock(listOf(TextSpan("Vorwort.")))) + document.blocks)
        val resolved = TextProgress.resolve(edited, last)!!
        assertEquals(last.offset + "Vorwort.".length + 2, resolved.offset)
        assertNull(TextProgress.resolve(document, ReadingPosition.InText(5, "Diesen Satz gibt es nicht.")))
        val middle = layout.length / 2
        TextProgress.contextSentence(layout, middle)
        TextProgress.remainingStart(layout, middle)
        TextProgress.fraction(layout, middle)
        val section = TextProgress.section(document, 1_000, layout.length - 1_000)
        assertTrue(section.plainText.length > layout.length - 2_100)
        return millis(start)
    }

    @Test
    fun layoutResolveAndSectionOfALongBook() {
        val book = book()
        assertTrue(book.plainText.length >= TextDocumentBuilder.DEFAULT_MAX_CHARS)
        val millis = measure(book)
        println("300 000 Zeichen in ${book.blocks.size} Absätzen: $millis ms")
        assertTrue("zu langsam: $millis ms", millis < 1_000)
    }

    @Test
    fun oneHugeParagraph() {
        val text = sentences.joinToString(" ").let { sentence -> generateSequence { sentence }.take(TextDocumentBuilder.DEFAULT_MAX_CHARS / sentence.length + 1).joinToString(" ") }
        val millis = measure(TextDocument(listOf(TextBlock(listOf(TextSpan(text))))))
        println("Ein Absatz mit ${text.length} Zeichen: $millis ms")
        assertTrue("zu langsam: $millis ms", millis < 1_000)
    }

    @Test
    fun writingALongBookAsMarkdown() {
        val book = book()
        val start = System.nanoTime()
        val markdown = MarkdownWriter.write(book)
        val builder = TextDocumentBuilder(Int.MAX_VALUE)
        MarkdownReader.read(markdown, builder)
        val read = builder.build()
        val millis = millis(start)
        println("Markdown schreiben und lesen, ${book.plainText.length} Zeichen: $millis ms")
        assertEquals(book.plainText, read.plainText)
        assertTrue("zu langsam: $millis ms", millis < 3_000)
    }
}
