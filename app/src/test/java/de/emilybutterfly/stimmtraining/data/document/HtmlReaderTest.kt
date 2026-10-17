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
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HtmlReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(html: String, charset: Charset = Charsets.UTF_8, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument =
        read(html.toByteArray(charset), maxChars)

    private fun read(bytes: ByteArray, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return DocumentText.read(file, DocumentKind.Html, maxChars)
    }

    @Test
    fun headingsFormattingAndEntities() {
        val document = read(
            """
            <!DOCTYPE html>
            <html lang="de">
            <head>
              <meta charset="utf-8">
              <title>Seitentitel</title>
              <style>p { color: red; } /* <b>kein Text</b> */</style>
              <script>var x = "<p>kein Text</p>"; if (a < b) {}</script>
            </head>
            <body>
              <!-- Kommentar <p>nicht sichtbar</p> -->
              <h1>Der   Nordwind</h1>
              <h2>Unter<br>titel</h2>
              <h4>Klein</h4>
              <p>Text mit <b>fett</b>, <strong>stark</strong>, <i>kursiv</i> und <em><b>beidem</b></em>.</p>
              <p>Entit&auml;ten: &amp; &lt;&gt; &quot;&#39; &#228;&#xE4; &euro;&nbsp;5 &#150; &unbekannt; AT&T</p>
              <p>Zeile eins<br>Zeile zwei<br/>
                 Zeile drei</p>
              <p>&nbsp;</p>
              <div>Ein <span style="font-weight:700">Google</span>-<span style="font-style: italic">Docs</span> Text</div>
              <noscript>Bitte JavaScript aktivieren</noscript><svg><text>SVG</text></svg>
              <select><option>Auswahl</option></select><textarea>Eingabe</textarea>
              <img src="data:image/png;base64,iVBORw0KGgo=" alt="Bild">
            </body>
            </html>
            """.trimIndent(),
        )
        assertOutline(
            document,
            "Heading1: Der Nordwind",
            "Heading2: Unter\ntitel",
            "Heading3: Klein",
            "Paragraph: Text mit fett, stark, kursiv und beidem.",
            "Paragraph: Entitäten: & <> \"' ää € 5 – &unbekannt; AT&T",
            "Paragraph: Zeile eins\nZeile zwei\nZeile drei",
            "Paragraph: Ein Google-Docs Text",
        )
        assertEquals("Text mit **fett**, **stark**, *kursiv* und ***beidem***.", document.blocks[3].markup())
        assertEquals("Ein **Google**-*Docs* Text", document.blocks[6].markup())
    }

    @Test
    fun listsWithLevelsAndNumbers() {
        val document = read(
            """
            <ul>
              <li>Atmung
                <ul>
                  <li>Zwerchfell</li>
                  <li><p>Flanken</p><p>Zweiter Absatz</p></li>
                </ul>
              </li>
              <li>Stütze
            </ul>
            <ol start="3">
              <li>Drei
              <li>Vier
              <li value="10">Zehn
            </ol>
            <p>Danach</p>
            """.trimIndent(),
        )
        assertOutline(
            document,
            "ListItem •: Atmung",
            "ListItem • @1: Zwerchfell",
            "ListItem • @1: Flanken",
            "ListItem @1: Zweiter Absatz",
            "ListItem •: Stütze",
            "ListItem 3.: Drei",
            "ListItem 4.: Vier",
            "ListItem 10.: Zehn",
            "Paragraph: Danach",
        )
    }

    @Test
    fun quotesPreformattedTextTablesAndRules() {
        val document = read(
            "<blockquote><p>Zitat eins</p>Zitat zwei</blockquote>\n" +
                "<pre>\n  Eingerückt\n    mehr   Platz\n</pre>\n" +
                "<table><tr><td>A</td><td>B</td></tr><tr><th>C</th><td>D</td></tr></table>\n" +
                "<p>Vor<hr>Nach</p>",
        )
        assertOutline(
            document,
            "Quote: Zitat eins",
            "Quote: Zitat zwei",
            "Paragraph: Eingerückt\n    mehr   Platz",
            "Paragraph: A B",
            "Paragraph: C D",
            "Paragraph: Vor",
            "Paragraph: Nach",
        )
    }

    @Test
    fun malformedMarkupIsTolerated() {
        val document = read(
            "<html><head><title>Titel</title><body>" +
                "<p>Ungeschlossen <b>fett<p>weiter <i>kursiv</b> nur kursiv</i> normal</p>\n" +
                "a < b und c > d, <3, <unbekannt attr=\"x>y\">Text</unbekannt>, <a href='x'",
        )
        assertEquals(
            listOf(
                listOf(TextSpan("Ungeschlossen "), TextSpan("fett", bold = true)),
                listOf(
                    TextSpan("weiter ", bold = true),
                    TextSpan("kursiv", bold = true, italic = true),
                    TextSpan(" nur kursiv", italic = true),
                    TextSpan(" normal"),
                ),
                listOf(TextSpan("a < b und c > d, <3, Text, <a href='x'")),
            ),
            document.blocks.map { it.spans },
        )
    }

    @Test
    fun missingHeadEndDoesNotSwallowTheText() {
        assertEquals("Text", read("<html><head><title>T</title><meta charset=utf-8><body><p>Text</p>").plainText)
    }

    @Test
    fun honoursDeclaredCharset() {
        val cyrillic = "<html><head><meta charset=\"windows-1251\"></head><body><p>Привет</p></body></html>"
        assertEquals("Привет", read(cyrillic, Charset.forName("windows-1251")).plainText)

        val legacy = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=iso-8859-1\"></head>" +
            "<body><p>Grüße – 5 €</p></body></html>"
        assertEquals("Grüße – 5 €", read(legacy, Charset.forName("windows-1252")).plainText)

        // Ohne Angabe: UTF-8, sonst Windows-1252; eine BOM geht vor.
        assertEquals("Grüße", read("<p>Grüße</p>").plainText)
        assertEquals("Grüße", read("<p>Grüße</p>", Charset.forName("windows-1252")).plainText)
        assertEquals("Grüße", read(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<p>Grüße</p>".toByteArray(Charsets.UTF_16LE)).plainText)
    }

    @Test
    fun longDocumentsAreTruncated() {
        val document = read("<p>" + "Sehr langer Text. ".repeat(1_000) + "</p>", maxChars = 1_000)
        assertTrue(document.truncated)
        assertEquals(1_000, document.blocks.single().text.length)
    }
}
