package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.assertOutline
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.markup
import org.junit.Assert.assertEquals
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
}
