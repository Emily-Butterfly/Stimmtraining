package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.assertOutline
import de.emilybutterfly.stimmtraining.data.document.TestDocuments.markup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OdtReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Automatische Vorlagen, wie LibreOffice sie in content.xml schreibt. */
    private val automaticStyles = """
        <style:style style:name="P1" style:family="paragraph" style:parent-style-name="Standard"><style:text-properties officeooo:rsid="0012" xmlns:officeooo="http://openoffice.org/2009/office"/></style:style>
        <style:style style:name="P2" style:family="paragraph" style:parent-style-name="Heading_20_2"><style:paragraph-properties fo:text-align="center"/></style:style>
        <style:style style:name="P3" style:family="paragraph" style:parent-style-name="Standard"><style:text-properties fo:font-weight="bold" style:font-weight-asian="bold"/></style:style>
        <style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold"/></style:style>
        <style:style style:name="T2" style:family="text"><style:text-properties fo:font-style="italic" style:font-style-asian="italic"/></style:style>
        <style:style style:name="T3" style:family="text"><style:text-properties fo:font-weight="700" fo:font-style="oblique"/></style:style>
        <style:style style:name="T4" style:family="text"><style:text-properties fo:font-weight="normal"/></style:style>
        <style:style style:name="T5" style:family="text" style:parent-style-name="Strong_20_Emphasis"/>
    """.trimIndent()

    /** Benannte Vorlagen aus styles.xml. */
    private val commonStyles = """
        <style:default-style style:family="paragraph"><style:text-properties fo:font-weight="bold"/></style:default-style>
        <style:style style:name="Standard" style:family="paragraph" style:class="text"/>
        <style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard"><style:text-properties fo:font-size="14pt"/></style:style>
        <style:style style:name="Heading_20_2" style:display-name="Heading 2" style:family="paragraph" style:parent-style-name="Heading"><style:text-properties fo:font-weight="bold"/></style:style>
        <style:style style:name="Title" style:family="paragraph" style:parent-style-name="Heading"/>
        <style:style style:name="Quotations" style:family="paragraph" style:parent-style-name="Standard"/>
        <style:style style:name="Mein_20_Zitat" style:display-name="Mein Zitat" style:family="paragraph" style:parent-style-name="Quotations"/>
        <style:style style:name="Strong_20_Emphasis" style:display-name="Strong Emphasis" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
    """.trimIndent()

    private fun read(body: String, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument =
        DocumentText.read(
            TestDocuments.odt(folder.newFile("test.odt"), body, automaticStyles, commonStyles),
            DocumentKind.Odt,
            maxChars,
        )

    @Test
    fun headingsTitleAndQuotes() {
        val document = read(
            """<text:p text:style-name="Title">Der Nordwind und die Sonne</text:p>""" +
                """<text:h text:style-name="Heading_20_1" text:outline-level="1">Kapitel</text:h>""" +
                """<text:h text:style-name="Heading_20_2" text:outline-level="2">Abschnitt</text:h>""" +
                """<text:h text:outline-level="4">Tief</text:h>""" +
                """<text:p text:style-name="P2">Über Elternvorlage</text:p>""" +
                """<text:p text:style-name="Mein_20_Zitat">Zitiert</text:p>""" +
                """<text:p text:style-name="P1">Fließtext</text:p>""",
        )
        assertOutline(
            document,
            "Heading1: Der Nordwind und die Sonne",
            "Heading1: Kapitel",
            "Heading2: Abschnitt",
            "Heading3: Tief",
            "Heading2: Über Elternvorlage",
            "Quote: Zitiert",
            "Paragraph: Fließtext",
        )
        // Der Fettdruck der Überschriftenvorlage wird nicht zur Textauszeichnung.
        assertEquals("Über Elternvorlage", document.blocks[4].markup())
    }

    @Test
    fun boldAndItalicFromAutomaticAndNamedStyles() {
        val document = read(
            """<text:p text:style-name="P1">Ganz <text:span text:style-name="T1">fett</text:span>, """ +
                """<text:span text:style-name="T2">kursiv</text:span>, <text:span text:style-name="T3">beides</text:span>, """ +
                """<text:span text:style-name="Strong_20_Emphasis">stark</text:span> und <text:span text:style-name="T5">geerbt</text:span>.</text:p>""" +
                """<text:p text:style-name="P3">Fetter Absatz <text:span text:style-name="T4">normal</text:span><text:span text:style-name="T2"> und kursiv</text:span></text:p>""",
        )
        assertEquals("Ganz **fett**, *kursiv*, ***beides***, **stark** und **geerbt**.", document.blocks[0].markup())
        // Der Absatz ist per Vorlage fett, einzelne Abschnitte heben das auf oder ergänzen kursiv.
        assertEquals("**Fetter Absatz **normal*** und kursiv***", document.blocks[1].markup())
    }

    @Test
    fun spacesTabsAndLineBreaks() {
        val document = read(
            """<text:p>Viel<text:s text:c="3"/>Platz und<text:s/>zwei, A<text:tab/>B</text:p>""" +
                """<text:p>Der Mond ist aufgegangen,<text:line-break/>die goldnen Sternlein prangen<text:line-break/>am Himmel hell und klar.</text:p>""",
        )
        assertOutline(
            document,
            "Paragraph: Viel   Platz und zwei, A\tB",
            "Paragraph: Der Mond ist aufgegangen,\ndie goldnen Sternlein prangen\nam Himmel hell und klar.",
        )
    }

    @Test
    fun whitespaceIsCollapsedLikeOdfRequires() {
        val document = read(
            """
            <text:p text:style-name="P1">
                Eingerückter
                <text:span text:style-name="T1">Quelltext</text:span>
                bleibt  lesbar
            </text:p>
            """,
        )
        assertEquals("Eingerückter **Quelltext** bleibt lesbar", document.blocks.single().markup())
    }

    @Test
    fun nestedListsWithLevelsAndMarkers() {
        val document = read(
            """<text:list text:style-name="L1">""" +
                """<text:list-item><text:p>Atmung</text:p>""" +
                """<text:list><text:list-item><text:p>Zwerchfell</text:p><text:p>Zweiter Absatz</text:p></text:list-item>""" +
                """<text:list-item><text:list><text:list-item><text:p>Ganz tief</text:p></text:list-item></text:list></text:list-item></text:list>""" +
                """</text:list-item>""" +
                """<text:list-header><text:p>Überschrift ohne Zeichen</text:p></text:list-header>""" +
                """<text:list-item><text:p/><text:p>Nach leerem Absatz</text:p></text:list-item>""" +
                """</text:list><text:p>Danach</text:p>""",
        )
        assertOutline(
            document,
            "ListItem •: Atmung",
            "ListItem • @1: Zwerchfell",
            "ListItem @1: Zweiter Absatz",
            "ListItem • @2: Ganz tief",
            "ListItem: Überschrift ohne Zeichen",
            "ListItem •: Nach leerem Absatz",
            "Paragraph: Danach",
        )
    }

    @Test
    fun notesAnnotationsAndTrackedChangesAreIgnored() {
        val document = read(
            """<text:tracked-changes><text:changed-region text:id="ct1"><text:deletion><office:change-info><dc:creator>Emily</dc:creator></office:change-info>""" +
                """<text:p>gelöschter Text</text:p></text:deletion></text:changed-region></text:tracked-changes>""" +
                """<text:p>Es war<text:change text:change-id="ct1"/> einmal<text:note text:id="ftn1" text:note-class="footnote"><text:note-citation>1</text:note-citation>""" +
                """<text:note-body><text:p>Fußnote</text:p></text:note-body></text:note><office:annotation><dc:creator>Emily</dc:creator><text:p>Kommentar</text:p></office:annotation>""" +
                """ ein <text:bookmark text:name="b"/>Tag.<text:soft-page-break/></text:p>""",
        )
        assertEquals("Es war einmal ein Tag.", document.plainText)
    }

    @Test
    fun framesTablesAndFieldsAreRead() {
        val document = read(
            """<text:p>Vor dem Rahmen <draw:frame draw:name="Rahmen1" svg:width="5cm"><svg:title>Alternativtext</svg:title>""" +
                """<draw:text-box><text:p>Im Rahmen</text:p></draw:text-box></draw:frame>nach dem Rahmen</text:p>""" +
                """<table:table table:name="T"><table:table-column/><table:table-row><table:table-cell><text:p>Zelle</text:p></table:table-cell></table:table-row></table:table>""" +
                """<text:p>Seite <text:page-number text:select-page="current">3</text:page-number> von <text:a xlink:href="https://example.org">Link</text:a></text:p>""" +
                """<text:table-of-content><text:table-of-content-source><text:index-title-template>Vorlage</text:index-title-template></text:table-of-content-source>""" +
                """<text:index-body><text:p>Kapitel 1</text:p></text:index-body></text:table-of-content>""",
        )
        assertOutline(
            document,
            "Paragraph: Vor dem Rahmen",
            "Paragraph: Im Rahmen",
            "Paragraph: nach dem Rahmen",
            "Paragraph: Zelle",
            "Paragraph: Seite 3 von Link",
            "Paragraph: Kapitel 1",
        )
    }

    @Test
    fun worksWithoutStylesXml() {
        val file = TestDocuments.odt(
            folder.newFile("plain.odt"),
            """<text:h text:outline-level="2">Abschnitt</text:h><text:p><text:span text:style-name="T1">fett</text:span></text:p>""",
            automaticStyles,
        )
        val document = DocumentText.read(file, DocumentKind.Odt)
        assertOutline(document, "Heading2: Abschnitt", "Paragraph: fett")
        assertEquals("**fett**", document.blocks[1].markup())
    }

    @Test
    fun damagedDocumentsThrowIOExceptionWithGermanMessage() {
        val brokenXml = TestDocuments.zip(
            folder.newFile("a.odt"),
            "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
            "content.xml" to "<office:document-content><text:p>kaputt".toByteArray(),
        )
        val noContent = TestDocuments.zip(folder.newFile("b.odt"), "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray())
        val wrongRoot = TestDocuments.zip(folder.newFile("c.odt"), "content.xml" to "<?xml version=\"1.0\"?><html/>".toByteArray())
        val notZip = folder.newFile("d.odt").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)) }
        for (file in listOf(brokenXml, noContent, wrongRoot, notZip)) {
            val error = assertThrows(file.name, IOException::class.java) { DocumentText.read(file, DocumentKind.Odt) }
            assertEquals(file.name, "Die Datei ist beschädigt oder kein gültiges OpenDocument-Dokument.", error.message)
        }
    }

    @Test
    fun zipBombIsStoppedByTheSizeLimit() {
        val file = TestDocuments.odt(folder.newFile("bomb.odt"), "<text:p/>".repeat(200_000))
        assertTrue(file.length() < 100_000)
        val error = assertThrows(IOException::class.java) { OdtReader.read(file, TextDocumentBuilder(), maxBytes = 500_000) }
        assertTrue(error.message!!, error.message!!.contains("zu groß"))
    }

    @Test
    fun longDocumentsAreTruncated() {
        val document = read((1..100).joinToString("") { "<text:p>Absatz $it mit etwas Text.</text:p>" }, maxChars = 300)
        assertTrue(document.truncated)
        assertTrue(document.blocks.sumOf { it.text.length } <= 300)
    }
}
