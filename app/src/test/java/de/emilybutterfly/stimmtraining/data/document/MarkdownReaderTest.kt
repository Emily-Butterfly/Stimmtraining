package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.assertOutline
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.markup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(markdown: String, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument {
        val file = folder.newFile().apply { writeText(markdown) }
        return DocumentText.read(file, DocumentKind.Markdown, maxChars)
    }

    /** Ein einzelner Absatz, mit Auszeichnung als Text. */
    private fun inline(markdown: String): String = read(markdown).blocks.single().markup()

    @Test
    fun atxAndSetextHeadings() {
        val document = read(
            """
            # Der Nordwind und die Sonne #
            ## Zweite Ebene
            ### Dritte
            ###### Sechste

            #Kein Titel

            Setext Eins
            ===========

            Setext Zwei
            ---
            Text
            """.trimIndent(),
        )
        assertOutline(
            document,
            "Heading1: Der Nordwind und die Sonne",
            "Heading2: Zweite Ebene",
            "Heading3: Dritte",
            "Heading3: Sechste",
            "Paragraph: #Kein Titel",
            "Heading1: Setext Eins",
            "Heading2: Setext Zwei",
            "Paragraph: Text",
        )
    }

    @Test
    fun boldAndItalic() {
        assertEquals(
            "**fett** und **auch fett**, *kursiv* und *auch kursiv*, ***beides***.",
            inline("**fett** und __auch fett__, *kursiv* und _auch kursiv_, ***beides***."),
        )
        assertEquals(
            listOf(
                TextSpan("fett mit ", bold = true),
                TextSpan("kursiv", bold = true, italic = true),
                TextSpan(" darin", bold = true),
                TextSpan(" und "),
                TextSpan("kursiv mit ", italic = true),
                TextSpan("fett", bold = true, italic = true),
                TextSpan(" darin", italic = true),
            ),
            read("**fett mit *kursiv* darin** und *kursiv mit **fett** darin*").blocks.single().spans,
        )
    }

    @Test
    fun unmatchedDelimitersStayLiteral() {
        val text = "snake_case_word bleibt, 5 * 3 = 15, ein * allein, Stern* am Ende, **nicht geschlossen, " +
            "Lehrer*innen und Schüler*innen, _halb"
        assertEquals(text, inline(text))
    }

    @Test
    fun codeEscapesLinksHtmlAndEntities() {
        assertEquals("**kein fett** und *Sternchen* und _Strich_ und `Backtick`", inline("`**kein fett**` und \\*Sternchen\\* und \\_Strich\\_ und `` `Backtick` ``"))
        assertEquals(
            "Link **fett** und Bild und https://example.org und Verweis und [nur Klammern]",
            inline("[Link **fett**](https://example.org \"Titel\") und ![Bild](bild.png) und <https://example.org> und [Verweis][1] und [nur Klammern]"),
        )
        assertEquals("Zeile\nUmbruch, Tag weg, ä & ©", inline("Zeile<br>Umbruch, <span class=\"x\">Tag</span> weg<!-- Kommentar -->, &auml; &amp; &copy;"))
    }

    @Test
    fun lineBreaksArePreservedAndEmphasisSpansLines() {
        val document = read(
            """
            Gedicht Zeile eins
            Gedicht Zeile zwei\
            Zeile drei mit Backslash

            *Ein kursives
            Gedicht*
            """.trimIndent(),
        )
        assertOutline(
            document,
            "Paragraph: Gedicht Zeile eins\nGedicht Zeile zwei\nZeile drei mit Backslash",
            "Paragraph: Ein kursives\nGedicht",
        )
        assertEquals("*Ein kursives\nGedicht*", document.blocks[1].markup())
    }

    @Test
    fun listsWithMarkersAndLevels() {
        val document = read(
            """
            - Atmung
              - Zwerchfell
                - Flanken
            - Stütze
            * Stern
            + Plus

            1. Eins
            2) Zwei
            10. Zehn
                weiter im Punkt

                Zweiter Absatz im Punkt

            Text danach

            - Eins
                - Vier Leerzeichen
                    - Acht
            	- Tabulator
            """.trimIndent(),
        )
        assertOutline(
            document,
            "ListItem •: Atmung",
            "ListItem • @1: Zwerchfell",
            "ListItem • @2: Flanken",
            "ListItem •: Stütze",
            "ListItem •: Stern",
            "ListItem •: Plus",
            "ListItem 1.: Eins",
            "ListItem 2.: Zwei",
            "ListItem 10.: Zehn\nweiter im Punkt",
            "ListItem: Zweiter Absatz im Punkt",
            "Paragraph: Text danach",
            "ListItem •: Eins",
            "ListItem • @1: Vier Leerzeichen",
            "ListItem • @2: Acht",
            "ListItem • @1: Tabulator",
        )
    }

    @Test
    fun listItemsInterruptParagraphsOnlyLikeCommonMark() {
        val document = read("Im Jahr\n1984. wurde\n- aber hier\nbeginnt eine Liste")
        assertOutline(document, "Paragraph: Im Jahr\n1984. wurde", "ListItem •: aber hier\nbeginnt eine Liste")
    }

    @Test
    fun quotesCodeBlocksRulesAndDefinitions() {
        val document = read(
            """
            > Zitat Zeile eins
            > Zeile zwei
            >
            > Neuer Absatz im *Zitat*
            faul fortgesetzt

            ```text
            **kein fett**
              eingerückt

            nach Leerzeile
            ```

            ***
            - - -
            ___

            [1]: https://example.org
            Ende
            """.trimIndent(),
        )
        assertOutline(
            document,
            "Quote: Zitat Zeile eins\nZeile zwei",
            "Quote: Neuer Absatz im Zitat\nfaul fortgesetzt",
            "Paragraph: **kein fett**\n  eingerückt\n\nnach Leerzeile",
            "Paragraph: Ende",
        )
        assertEquals("Neuer Absatz im *Zitat*\nfaul fortgesetzt", document.blocks[1].markup())
    }

    @Test
    fun unclosedCodeFenceRunsToTheEnd() {
        assertOutline(read("Text\n~~~\ncode *bleibt*\n"), "Paragraph: Text", "Paragraph: code *bleibt*")
    }

    @Test
    fun frontMatterIsSkipped() {
        assertOutline(read("---\ntitle: Nordwind\nauthor: Äsop\n---\n# Titel\nText"), "Heading1: Titel", "Paragraph: Text")
        // Ohne "schlüssel:" ist es eine Trennlinie mit Text danach.
        assertOutline(read("---\nKein Kopf\n"), "Paragraph: Kein Kopf")
    }

    @Test
    fun manyDelimitersDoNotHang() {
        val start = System.nanoTime()
        val document = read("*a ".repeat(20_000) + "\n\n" + "a* ".repeat(5_000) + "**b ".repeat(5_000))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertEquals(2, document.blocks.size)
    }

    @Test
    fun longDocumentsAreTruncated() {
        val document = read((1..200).joinToString("\n\n") { "Absatz **$it** mit Text." }, maxChars = 200)
        assertTrue(document.truncated)
        assertTrue(document.blocks.sumOf { it.text.length } <= 200)
    }

    private val longLines = (1..200).map { "Zeile $it mit etwas Text, damit der Absatz lang wird." }

    @Test
    fun longParagraphsAndCodeBlocksAreSplitAtLineBreaks() {
        val paragraph = read(longLines.joinToString("\n"))
        assertTrue(paragraph.blocks.size > 3)
        assertTrue(paragraph.blocks.all { it.style == BlockStyle.Paragraph && it.text.length <= TextDocumentBuilder.LONG_BLOCK_CHARS + 60 })
        assertEquals(longLines, paragraph.blocks.flatMap { it.text.split("\n") })

        // Hervorhebung über die Teilung hinweg bleibt erhalten.
        val italic = read("*" + longLines.joinToString("\n") + "*")
        assertTrue(italic.blocks.size > 3)
        assertTrue(italic.blocks.all { block -> block.spans.all { it.italic } })

        val code = read("```\n" + longLines.joinToString("\n") + "\n```\nDanach")
        assertTrue(code.blocks.size > 4)
        assertEquals(longLines, code.blocks.dropLast(1).flatMap { it.text.split("\n") })
        assertEquals("Danach", code.blocks.last().text)

        // Ein langer Listenpunkt: Das Aufzählungszeichen steht nur am ersten Teil.
        val item = read("- " + longLines.joinToString("\n  "))
        assertTrue(item.blocks.size > 3)
        assertTrue(item.blocks.all { it.style == BlockStyle.ListItem })
        assertEquals("•", item.blocks.first().marker)
        item.blocks.drop(1).forEach { assertNull(it.marker) }
        assertEquals(longLines, item.blocks.flatMap { it.text.split("\n") })
    }

    @Test
    fun manyAngleBracketsAndBackticksDoNotHang() {
        val paragraphs = listOf(
            "<".repeat(300_000),
            "<a".repeat(150_000),
            "<!--".repeat(75_000),
            "<a@b".repeat(75_000),
            "\\``".repeat(100_000),
        )
        for (paragraph in paragraphs) {
            val start = System.nanoTime()
            val document = read(paragraph)
            val millis = (System.nanoTime() - start) / 1_000_000
            assertTrue("${paragraph.take(4)}: $millis ms", millis < 2_000)
            assertTrue(paragraph.take(4), document.plainText.length > 100_000)
        }
        // Gekürzte Suche ändert nichts an kurzen Tags, Adressen und Kommentaren.
        assertEquals("mail@example.org, https://example.org/a?b=c und Text", inline("<mail@example.org>, <https://example.org/a?b=c> <!-- weg -->und <em class=\"x\">Text</em>"))
        assertEquals("Offen <!-- ohne Ende und <!-- noch einmal", inline("Offen <!-- ohne Ende und <!-- noch einmal"))
    }

    @Test
    fun veryLongLinesDoNotOverflowOrHang() {
        val start = System.nanoTime()
        assertOutline(read("# Titel" + " ".repeat(100_000) + "Ende #"), "Heading1: Titel" + " ".repeat(100_000) + "Ende")
        assertOutline(read("> ".repeat(100_000) + "Zitat"), "Quote: Zitat")
        assertEquals(1, read("* ".repeat(100_000) + "x").blocks.size)
        assertEquals(0, read("- ".repeat(100_000)).blocks.size)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        // Schließende Rauten wie bisher: nur durch Leerraum abgetrennte fallen weg.
        assertOutline(read("## Titel ##\n\n## Titel#\n\n# #"), "Heading2: Titel", "Heading2: Titel#", "Heading1: #")
    }
}
