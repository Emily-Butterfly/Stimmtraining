package de.emilybutterfly.stimmtraining.data.library

import de.emilybutterfly.stimmtraining.data.document.BlockStyle
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import de.emilybutterfly.stimmtraining.data.document.DocumentText
import de.emilybutterfly.stimmtraining.data.document.MarkdownReader
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.assertOutline
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.markup
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.outline
import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import de.emilybutterfly.stimmtraining.data.document.TextDocumentBuilder
import de.emilybutterfly.stimmtraining.data.document.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class MarkdownWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(markdown: String): TextDocument {
        val builder = TextDocumentBuilder()
        MarkdownReader.read(markdown, builder)
        return builder.build()
    }

    private fun plain(text: String, style: BlockStyle = BlockStyle.Paragraph, marker: String? = null, level: Int = 0) =
        TextBlock(listOf(TextSpan(text)), style, marker, level)

    private fun document(vararg blocks: TextBlock) = TextDocument(blocks.toList())

    /** Schreibt [document] und liest es zurück: gleicher Aufbau, gleicher Text, gleiche Hervorhebung. */
    private fun assertRoundTrip(document: TextDocument): String {
        val markdown = MarkdownWriter.write(document)
        val read = read(markdown)
        assertEquals(markdown, document.outline().joinToString("\n"), read.outline().joinToString("\n"))
        assertEquals(markdown, document.blocks.map { it.markup() }, read.blocks.map { it.markup() })
        return markdown
    }

    @Test
    fun writesReadableMarkdown() {
        val document = document(
            plain("Der Nordwind und die Sonne", BlockStyle.Heading1),
            TextBlock(listOf(TextSpan("Einst stritten sich "), TextSpan("Nordwind", bold = true), TextSpan(" und "), TextSpan("Sonne", italic = true), TextSpan("."))),
            plain("Atmung", BlockStyle.ListItem, "•"),
            plain("Zwerchfell", BlockStyle.ListItem, "•", 1),
            plain("Eins", BlockStyle.ListItem, "1."),
            plain("Zitat\nZeile zwei", BlockStyle.Quote),
            plain("Gedicht\nZeile"),
            plain("Unterkapitel", BlockStyle.Heading3),
        )
        val expected = """
            # Der Nordwind und die Sonne

            Einst stritten sich **Nordwind** und *Sonne*.

            - Atmung

              - Zwerchfell

            1. Eins

            > Zitat\
            > Zeile zwei

            Gedicht\
            Zeile

            ### Unterkapitel
        """.trimIndent() + "\n"
        assertEquals(expected, MarkdownWriter.write(document))
        assertRoundTrip(document)
        assertEquals("", MarkdownWriter.write(TextDocument(emptyList())))
        assertEquals("", MarkdownWriter.write(document(plain("   "), TextBlock(emptyList()))))
    }

    @Test
    fun escapesWhatMarkdownWouldInterpret() {
        fun written(text: String) = MarkdownWriter.write(document(plain(text))).removeSuffix("\n")
        assertEquals("\\# keine Überschrift", written("# keine Überschrift"))
        assertEquals("1\\. keine Liste", written("1. keine Liste"))
        assertEquals("1.5 Liter", written("1.5 Liter"))
        assertEquals("\\- kein Punkt, aber - mitten", written("- kein Punkt, aber - mitten"))
        assertEquals("\\> kein Zitat, a > b", written("> kein Zitat, a > b"))
        assertEquals("a\\_b\\*c & d\\&amp; \\&\\#228;", written("a_b*c & d&amp; &#228;"))
        assertEquals("\\<b>fett\\</b> Komm! \\[x\\]", written("<b>fett</b> Komm! [x]"))
        assertEquals("C:\\\\Pfad \\`code\\` \\~ \\|", written("C:\\Pfad `code` ~ |"))
        assertEquals("Zeile\\\n&#32; eingerückt\\\n\\\nnach Leerzeile", written("Zeile\n  eingerückt\n\nnach Leerzeile"))
        assertEquals("Zeile\\\n\\---", written("Zeile\n---"))
        assertEquals("# Zeile<br>zwei", MarkdownWriter.write(document(plain("Zeile\nzwei", BlockStyle.Heading1))).removeSuffix("\n"))
    }

    private val tricky = listOf(
        "*Sternchen* und _Unterstriche_ und **doppelt** und __doppelt__",
        "# keine Überschrift", "#hashtag und Rauten #", "Titel ##", "###### sechs",
        "1. keine Liste", "1) auch keine", "2024. war ein Jahr", "123456789012. lang", "10.5 Liter", "3)",
        "- kein Punkt", "-- Gedankenstrich", "+ kein Plus", "* kein Stern", "> kein Zitat", ">> auch nicht", "= kein Setext",
        "&amp; bleibt &amp; und &#228; und &#x41; und &auml; und &nbsp; und & allein und A&B und &;",
        "<b>kein Tag</b> und <br> und <https://example.org> und <mail@example.org> und <!-- kein Kommentar -->",
        "Backslash \\ und \\* und C:\\Pfad\\Datei und \\\\doppelt",
        "Ende mit Backslash\\",
        "URL https://example.org/a_b?c=d&e=f#anker und www.example.org/*x*",
        "[kein Link](https://example.org) und ![kein Bild](x.png) und [Verweis][1]",
        "[1]: https://example.org",
        "`kein Code` und ``doppelt`` und ```",
        "```kein Zaun", "~~~", "~~nicht durchgestrichen~~",
        "---", "***", "___", "- - -", "* * *", "===", "|a|b|", "a | b",
        "Lehrer*innen und 5 * 3 = 15 und snake_case_wort",
        "Zeile\n---", "Zeile\n===", "Zeile\n- Punkt", "Zeile\n1. Punkt", "Zeile\n> Zitat", "Zeile\n# Titel",
        "Zeile\n```", "Zeile\n    eingerückt", "Zeile\n\tTab", "Zeile\n[1]: https://example.org",
        "Zeile\n\nnach Leerzeile", "Zeile\n   \nnach Leerraum", "Zeile mit Leerzeichen am Ende   \nweiter",
        "Zeile\n\u00A0geschützt", "Tab\tmitten", "Wagen\rRücklauf", "Zeilen\u2028trenner\u2029Absatz",
        "Seiten\u000Cvorschub, \u000B, \u007F und \u0007", "Mitten\uFEFFdrin",
        "\\", "#", "-", "1.", "|", "&", "<", ">", "*", "**", "_", "!", "[", "]", "`", "~", "=", "+", "<!---->",
        "Fußnote[^1] und Häkchen [ ] und [x]",
        "\"Anführung\" und „deutsch“ und »französisch« 😀 🎉",
        "---\ntitle: kein Kopf\n---",
        "Er sagte: \\*nicht\\*",
    )

    @Test
    fun trickyTextSurvivesInEveryBlockStyle() {
        val styles = listOf(
            { text: String -> plain(text) },
            { text: String -> plain(text, BlockStyle.Heading1) },
            { text: String -> plain(text, BlockStyle.Heading2) },
            { text: String -> plain(text, BlockStyle.Heading3) },
            { text: String -> plain(text, BlockStyle.Quote) },
            { text: String -> plain(text, BlockStyle.ListItem, "•") },
            { text: String -> plain(text, BlockStyle.ListItem, "7.") },
            { text: String -> plain(text, BlockStyle.ListItem, "•", 1) },
        )
        for (style in styles) {
            for (text in tricky) {
                // Allein (auch als erste Zeile der Datei) …
                val single = if (style(text).level > 0) document(plain("Oben", BlockStyle.ListItem, "•"), style(text)) else document(style(text))
                assertRoundTrip(single)
            }
            // … und alle hintereinander.
            val all = tricky.map(style)
            assertRoundTrip(TextDocument(if (all.first().level > 0) listOf(plain("Oben", BlockStyle.ListItem, "•")) + all else all))
        }
    }

    @Test
    fun throughTheMarkdownFileReader() {
        // Mit dem BOM-Absatz vorn: Ein Zeichen U+FEFF am Dateianfang entfernte das Einlesen sonst.
        val document = TextDocument(listOf(plain("\uFEFFBOM am Anfang")) + tricky.map { plain(it) } + plain("Titel\nzweite Zeile", BlockStyle.Heading2))
        val file = folder.newFile("abschnitt.md").apply { writeText(MarkdownWriter.write(document)) }
        assertEquals(document.outline(), DocumentText.read(file, DocumentKind.Markdown).outline())
    }

    private val emphasized = listOf(
        listOf(TextSpan("Das ist "), TextSpan("wichtig", bold = true), TextSpan(".")),
        listOf(TextSpan("beides", bold = true, italic = true)),
        listOf(
            TextSpan("fett mit ", bold = true), TextSpan("kursiv", bold = true, italic = true), TextSpan(" darin", bold = true),
            TextSpan(" und "), TextSpan("kursiv mit ", italic = true), TextSpan("fett", bold = true, italic = true), TextSpan(" darin", italic = true),
        ),
        // Mitten im Wort und an Satzzeichen
        listOf(TextSpan("un", italic = true), TextSpan("möglich")),
        listOf(TextSpan("Wort"), TextSpan("fett", bold = true), TextSpan("Wort")),
        listOf(TextSpan("x"), TextSpan("(fett)", bold = true), TextSpan("y")),
        listOf(TextSpan("a"), TextSpan("!", italic = true), TextSpan("b")),
        listOf(TextSpan("„Hallo“", bold = true), TextSpan(", sagte er.")),
        listOf(TextSpan("Ende "), TextSpan("!", bold = true)),
        listOf(TextSpan("Lehrer"), TextSpan("*innen", italic = true)),
        listOf(TextSpan("Emoji "), TextSpan("😀", bold = true), TextSpan("x")),
        // Über Zeilen hinweg und am Zeilenanfang
        listOf(TextSpan("Zeile eins\nZeile zwei", italic = true)),
        listOf(TextSpan("Zeile eins\n"), TextSpan("kursiv", italic = true), TextSpan(" am Zeilenanfang")),
        listOf(TextSpan("fett", bold = true), TextSpan("\n"), TextSpan("fett", bold = true)),
        // Sonderzeichen in der Hervorhebung
        listOf(TextSpan("a*b_c", bold = true)),
        listOf(TextSpan("# Titel", bold = true)),
        listOf(TextSpan("- Punkt", italic = true)),
        listOf(TextSpan("1. Punkt", bold = true)),
        listOf(TextSpan("> Zitat", bold = true, italic = true)),
        listOf(TextSpan("*", bold = true)),
        listOf(TextSpan("\\", italic = true), TextSpan("x")),
        // Wechsel ohne Leerraum dazwischen
        listOf(TextSpan("fett", bold = true), TextSpan("kursiv", italic = true)),
        listOf(TextSpan("kursiv", italic = true), TextSpan("beides", bold = true, italic = true), TextSpan("fett", bold = true)),
        listOf(TextSpan("beides", bold = true, italic = true), TextSpan("nur fett", bold = true), TextSpan("beides", bold = true, italic = true)),
        listOf(TextSpan("a "), TextSpan("b", bold = true), TextSpan(" "), TextSpan("c", bold = true)),
        // Beide beginnen zugleich, eine endet früher: Die längere steht außen.
        listOf(TextSpan("Wichtig und kursiv", bold = true, italic = true), TextSpan(" nur kursiv", italic = true)),
        listOf(TextSpan("Wichtig und fett", bold = true, italic = true), TextSpan(" nur fett", bold = true), TextSpan(".")),
        listOf(TextSpan("Nur fett ", bold = true), TextSpan("und kursiv", bold = true, italic = true)),
        // Überkreuzt, ohne Leerraum an der Stelle
        listOf(TextSpan("fett", bold = true), TextSpan("beides", bold = true, italic = true), TextSpan("kursiv", italic = true)),
    )

    @Test
    fun boldAndItalicSurvive() {
        for (spans in emphasized) {
            assertRoundTrip(document(TextBlock(spans)))
            assertRoundTrip(document(TextBlock(spans, BlockStyle.Heading2)))
            assertRoundTrip(document(TextBlock(spans, BlockStyle.ListItem, "•")))
            assertRoundTrip(document(TextBlock(spans, BlockStyle.Quote)))
        }
        assertTrue("Kommentar trennt nur, wo nötig", "<!---->" !in MarkdownWriter.write(document(TextBlock(emphasized[2]))))
        assertTrue("*un*<!---->möglich" in MarkdownWriter.write(document(TextBlock(emphasized[3]))))
    }

    @Test
    fun emphasisOnWhitespaceAtTheEdgeIsDropped() {
        val document = document(TextBlock(listOf(TextSpan("fett ", bold = true), TextSpan("normal"), TextSpan(" kursiv", italic = true))))
        val markdown = MarkdownWriter.write(document)
        assertEquals("**fett** normal *kursiv*\n", markdown)
        assertEquals(listOf(TextSpan("fett", bold = true), TextSpan(" normal "), TextSpan("kursiv", italic = true)), read(markdown).blocks.single().spans)

        // Überkreuzt am Leerzeichen: Kursiv müsste vor dem Leerzeichen neu beginnen, also verliert nur dieses die Hervorhebung.
        val crossing = document(TextBlock(listOf(TextSpan("fett ", bold = true), TextSpan("beides", bold = true, italic = true), TextSpan(" kursiv", italic = true))))
        assertEquals("**fett *beides*** *kursiv*\n", MarkdownWriter.write(crossing))
        assertEquals(
            listOf(TextSpan("fett ", bold = true), TextSpan("beides", bold = true, italic = true), TextSpan(" "), TextSpan("kursiv", italic = true)),
            read(MarkdownWriter.write(crossing)).blocks.single().spans,
        )
    }

    @Test
    fun unrepresentableEmphasisFallsBackToPlainText() {
        // Kursiv neu geöffnet direkt vor einem Satzzeichen, während Fett aus „***“ noch offen ist: Der
        // Leser würde die Sterne anders paaren. Der Text bleibt dann unverändert, nur ohne Hervorhebung.
        val spans = listOf(TextSpan("a", bold = true, italic = true), TextSpan("b", bold = true), TextSpan("(c)", bold = true, italic = true))
        val read = read(MarkdownWriter.write(document(TextBlock(spans)))).blocks.single()
        assertEquals("ab(c)", read.text)
        assertEquals(listOf(TextSpan("ab(c)")), read.spans)

        // Mehr Hervorhebungen, als der Leser in einem Absatz verarbeitet
        val many = TextBlock((1..1_500).flatMap { listOf(TextSpan("Wort$it", bold = true), TextSpan(" ")) }.dropLast(1))
        val readMany = read(MarkdownWriter.write(document(many))).blocks.single()
        assertEquals(many.text, readMany.text)
        assertFalse(readMany.text.contains('*'))
    }

    @Test
    fun nestedListsKeepTheirLevels() {
        assertRoundTrip(
            document(
                plain("Atmung", BlockStyle.ListItem, "•"),
                plain("Zwerchfell", BlockStyle.ListItem, "•", 1),
                plain("Flanken", BlockStyle.ListItem, "1.", 2),
                plain("Rücken", BlockStyle.ListItem, "10.", 2),
                plain("Unter zehn", BlockStyle.ListItem, "•", 3),
                plain("Weiterer Absatz im Punkt", BlockStyle.ListItem, null, 3),
                plain("Zurück auf Ebene 1", BlockStyle.ListItem, null, 1),
                plain("Stütze", BlockStyle.ListItem, "2.", 0),
                plain("Text danach"),
                plain("Neue Liste", BlockStyle.ListItem, "•"),
                plain("Mehrzeiliger\nPunkt\n- kein Unterpunkt", BlockStyle.ListItem, "•", 1),
                plain("Zitat danach", BlockStyle.Quote),
                plain("Liste nach Zitat", BlockStyle.ListItem, "01."),
                plain("# Überschrift danach", BlockStyle.Heading2),
                plain("Liste nach Überschrift", BlockStyle.ListItem, "•"),
            ),
        )
    }

    @Test
    fun listLimitsOfMarkdown() {
        // Ebenen können nicht übersprungen werden; andere Aufzählungszeichen werden „•“.
        val document = document(
            plain("Eins", BlockStyle.ListItem, "•", 2),
            plain("Zwei", BlockStyle.ListItem, "a)", 3),
            plain("Drei", BlockStyle.ListItem, "◦", 0),
            plain("Vier", BlockStyle.ListItem, "1.1.", 1),
            plain("Text"),
            plain("Absatz ohne Punkt davor", BlockStyle.ListItem, null, 1),
        )
        assertOutline(
            read(MarkdownWriter.write(document)),
            "ListItem •: Eins",
            "ListItem • @1: Zwei",
            "ListItem •: Drei",
            "ListItem • @1: Vier",
            "Paragraph: Text",
            "Paragraph: Absatz ohne Punkt davor",
        )
    }

    @Test
    fun longParagraphsWithLineBreaksAreSplitLikeOnImport() {
        val lines = (1..200).map { "Zeile $it mit etwas Text, damit der Absatz lang wird." }
        val document = document(TextBlock(listOf(TextSpan(lines.joinToString("\n"), italic = true))))
        val read = read(MarkdownWriter.write(document))
        assertTrue(read.blocks.size > 1)
        assertEquals(lines, read.blocks.flatMap { it.text.split("\n") })
        assertTrue(read.blocks.all { block -> block.spans.all { it.italic } })
        // Ohne Zeilenumbruch bleibt auch ein langer Absatz ganz.
        assertRoundTrip(document(plain(lines.joinToString(" "))))
    }

    @Test
    fun randomDocumentsRoundTrip() {
        val alphabet = "aZöß 1.0*_#-+>=<&;[]()!`~|\\\n\t»„“\"'😀:/!".toList() + listOf("&amp;", "<br>", "**", "1. ", "- ", "> ", "\n# ", "\n---\n", "  ", "Wort", "&#228;")
        val random = Random(42)
        var blocks = 0
        var fallbacks = 0
        repeat(400) {
            val builder = TextDocumentBuilder()
            var depth = 0
            repeat(random.nextInt(1, 8)) {
                repeat(random.nextInt(1, 6)) {
                    val text = (0 until random.nextInt(1, 12)).joinToString("") { alphabet[random.nextInt(alphabet.size)].toString() }
                    builder.append(text, bold = random.nextInt(3) == 0, italic = random.nextInt(3) == 0)
                }
                when (random.nextInt(7)) {
                    0 -> builder.endBlock(BlockStyle.Heading1)
                    1 -> builder.endBlock(BlockStyle.Quote)
                    2, 3 -> {
                        val level = random.nextInt(0, depth + 1)
                        builder.endBlock(BlockStyle.ListItem, if (random.nextBoolean()) "•" else "${random.nextInt(1, 30)}.", level)
                        depth = level + 1
                    }
                    4 -> if (depth > 0) builder.endBlock(BlockStyle.ListItem, null, random.nextInt(0, depth)) else builder.endBlock()
                    else -> builder.endBlock()
                }
                // Was nicht zur Liste gehört, beendet sie (wie in MarkdownReader).
                if (builder.build().blocks.lastOrNull()?.style != BlockStyle.ListItem) depth = 0
            }
            val document = builder.build()
            val markdown = MarkdownWriter.write(document)
            val read = read(markdown)
            assertEquals(markdown, document.outline(), read.outline())
            document.blocks.zip(read.blocks).forEach { (original, back) ->
                blocks++
                if (visible(original) != visible(back)) {
                    fallbacks++
                    assertTrue("$markdown\n→ ${back.markup()}", back.spans.none { it.bold || it.italic })
                }
            }
        }
        println("Zufallsdokumente: $fallbacks von $blocks Absätzen ohne Hervorhebung geschrieben")
        // Die Zufallstexte bestehen fast nur aus Satzzeichen; in echten Texten ist der Rückfall noch seltener.
        assertTrue("$fallbacks von $blocks", fallbacks * 50 < blocks)
    }

    /** Sichtbare Zeichen mit ihrer Hervorhebung; Leerraum am Rand einer Hervorhebung zählt nicht. */
    private fun visible(block: TextBlock): List<String> = block.spans.flatMap { span ->
        span.text.filterNot { it.isWhitespace() }.map { "$it${if (span.bold) "b" else ""}${if (span.italic) "i" else ""}" }
    }
}
