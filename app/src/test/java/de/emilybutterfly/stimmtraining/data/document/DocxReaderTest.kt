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
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocxReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(body: String, styles: String? = null, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument =
        DocumentText.read(TestDocuments.docx(folder.newFile("test.docx"), body, styles), DocumentKind.Docx, maxChars)

    private fun p(text: String, style: String? = null) =
        """<w:p w:rsidR="00A1"><w:pPr>${style?.let { """<w:pStyle w:val="$it"/>""" } ?: ""}<w:rPr><w:b/></w:rPr></w:pPr><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private val headingStyles = """
        <w:style w:type="paragraph" w:default="1" w:styleId="Standard"><w:name w:val="Normal"/><w:qFormat/></w:style>
        <w:style w:type="paragraph" w:styleId="berschrift1"><w:name w:val="heading 1"/><w:basedOn w:val="Standard"/><w:next w:val="Standard"/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:customStyle="1" w:styleId="MeineUeberschrift"><w:name w:val="Meine Überschrift"/><w:basedOn w:val="berschrift1"/></w:style>
        <w:style w:type="paragraph" w:styleId="Zitat"><w:name w:val="Quote"/><w:basedOn w:val="Standard"/><w:rPr><w:i/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Aufzhlungszeichen"><w:name w:val="List Bullet"/><w:basedOn w:val="Standard"/><w:pPr><w:numPr><w:numId w:val="3"/></w:numPr></w:pPr></w:style>
        <w:style w:type="character" w:styleId="Fett"><w:name w:val="Strong"/><w:rPr><w:b/><w:bCs/></w:rPr></w:style>
        <w:style w:type="table" w:styleId="Tabellengitternetz"><w:name w:val="Table Grid"/><w:tblStylePr w:type="firstRow"><w:rPr><w:b/></w:rPr></w:tblStylePr></w:style>
    """.trimIndent()

    @Test
    fun readsHeadingsByStyleIdAndName() {
        val document = read(
            p("Der Nordwind und die Sonne", "Title") +
                p("Untertitel", "Subtitle") +
                p("Erstes Kapitel", "berschrift1") +
                p("Zweite Ebene", "berschrift2") +
                p("Vierte Ebene", "Heading4") +
                p("Eigene Überschrift", "MeineUeberschrift") +
                p("Ein Zitat", "Zitat") +
                p("Normaler Text", "Standard"),
            headingStyles,
        )
        assertOutline(
            document,
            "Heading1: Der Nordwind und die Sonne",
            "Heading2: Untertitel",
            "Heading1: Erstes Kapitel",
            "Heading2: Zweite Ebene",
            "Heading3: Vierte Ebene",
            "Heading1: Eigene Überschrift",
            "Quote: Ein Zitat",
            "Paragraph: Normaler Text",
        )
    }

    @Test
    fun headingsWorkWithoutStylesXml() {
        val document = read(p("Kapitel", "Heading1") + p("Abschnitt", "berschrift2") + p("Text"))
        assertOutline(document, "Heading1: Kapitel", "Heading2: Abschnitt", "Paragraph: Text")
    }

    @Test
    fun boldAndItalicRunsAndExplicitOff() {
        val body = """<w:p><w:r><w:t xml:space="preserve">Ganz </w:t></w:r>""" +
            """<w:r><w:rPr><w:b/><w:bCs/></w:rPr><w:t>fett</w:t></w:r>""" +
            """<w:r><w:t xml:space="preserve">, </w:t></w:r>""" +
            """<w:r><w:rPr><w:i/></w:rPr><w:t>kursiv</w:t></w:r>""" +
            """<w:r><w:t xml:space="preserve"> und </w:t></w:r>""" +
            """<w:r><w:rPr><w:b w:val="1"/><w:i w:val="true"/></w:rPr><w:t>beides</w:t></w:r>""" +
            """<w:r><w:rPr><w:b w:val="0"/><w:i w:val="false"/></w:rPr><w:t xml:space="preserve"> – nicht </w:t></w:r>""" +
            """<w:r><w:rPr><w:bCs/><w:iCs/></w:rPr><w:t>komplex</w:t></w:r>""" +
            """<w:r><w:rPr><w:rStyle w:val="Fett"/></w:rPr><w:t xml:space="preserve"> stark</w:t></w:r>""" +
            """<w:r><w:rPr><w:rStyle w:val="Fett"/><w:b w:val="off"/></w:rPr><w:t xml:space="preserve"> aus</w:t></w:r></w:p>"""
        val block = read(body, headingStyles).blocks.single()
        assertEquals("Ganz **fett**, *kursiv* und ***beides*** – nicht komplex** stark** aus", block.markup())
    }

    @Test
    fun characterStyleNamesAreRecognisedWithoutStylesXml() {
        val body = """<w:p><w:r><w:rPr><w:rStyle w:val="Fett"/></w:rPr><w:t>stark</w:t></w:r>""" +
            """<w:r><w:rPr><w:rStyle w:val="Hervorhebung"/></w:rPr><w:t>betont</w:t></w:r></w:p>"""
        assertEquals("**stark***betont*", read(body).blocks.single().markup())
    }

    @Test
    fun paragraphMarkFormattingDoesNotLeakIntoText() {
        // <w:pPr><w:rPr><w:b/> formatiert nur die Absatzmarke.
        assertEquals("Text", read(p("Text")).blocks.single().markup())
    }

    @Test
    fun listsWithLevelsAndListStyles() {
        fun item(text: String, level: Int, numId: Int = 1) =
            """<w:p><w:pPr><w:pStyle w:val="Listenabsatz"/><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$numId"/></w:numPr><w:ind w:left="720"/></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
        val document = read(
            item("Atmung", 0) + item("Zwerchfell", 1) + item("Flanken", 2) + item("Stütze", 0) +
                item("Keine Liste", 0, numId = 0) + p("Über Vorlage", "Aufzhlungszeichen"),
            headingStyles,
        )
        assertOutline(
            document,
            "ListItem •: Atmung",
            "ListItem • @1: Zwerchfell",
            "ListItem • @2: Flanken",
            "ListItem •: Stütze",
            "Paragraph: Keine Liste",
            "ListItem •: Über Vorlage",
        )
    }

    @Test
    fun breaksTabsAndHyphens() {
        val body = """<w:p><w:r><w:t>Zeile eins</w:t><w:br/><w:t>Zeile zwei</w:t><w:cr/><w:t>Zeile drei</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:noBreakHyphen/><w:t>C</w:t><w:softHyphen/><w:t>D</w:t><w:sym w:font="Wingdings" w:char="F04A"/></w:r></w:p>""" +
            """<w:p><w:r><w:t>Vor dem Umbruch</w:t><w:br w:type="page"/><w:t>Neue Seite</w:t></w:r></w:p>"""
        assertOutline(
            read(body),
            "Paragraph: Zeile eins\nZeile zwei\nZeile drei",
            "Paragraph: A\tB-CD",
            "Paragraph: Vor dem Umbruch",
            "Paragraph: Neue Seite",
        )
    }

    @Test
    fun ignoresDeletedTextFieldCodesAndHiddenText() {
        val body = """<w:p><w:r><w:t xml:space="preserve">Es war </w:t></w:r>""" +
            """<w:del w:id="1" w:author="Emily" w:date="2026-01-01T00:00:00Z"><w:r><w:delText>nicht </w:delText></w:r></w:del>""" +
            """<w:ins w:id="2" w:author="Emily" w:date="2026-01-01T00:00:00Z"><w:r><w:t xml:space="preserve">einmal </w:t></w:r></w:ins>""" +
            """<w:moveFrom w:id="3" w:author="Emily"><w:r><w:t>verschoben</w:t></w:r></w:moveFrom>""" +
            """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE \* MERGEFORMAT </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>""" +
            """<w:r><w:rPr><w:vanish/></w:rPr><w:t>versteckt</w:t></w:r>""" +
            """<w:r><w:rPr><w:rPrChange w:id="4" w:author="Emily"><w:rPr><w:b/></w:rPr></w:rPrChange></w:rPr><w:t xml:space="preserve"> Tag</w:t></w:r>""" +
            """<w:r><w:footnoteReference w:id="1"/></w:r><w:commentRangeStart w:id="0"/><w:r><w:commentReference w:id="0"/></w:r></w:p>"""
        assertEquals("Es war einmal 1 Tag", read(body).plainText)
    }

    @Test
    fun hyperlinksTablesAndContentControlsAreRead() {
        val body = """<w:p><w:hyperlink r:id="rId5"><w:r><w:rPr><w:rStyle w:val="Hyperlink"/></w:rPr><w:t>Link</w:t></w:r></w:hyperlink></w:p>""" +
            """<w:tbl><w:tblPr><w:tblStyle w:val="Tabellengitternetz"/></w:tblPr><w:tblGrid><w:gridCol w:w="4531"/></w:tblGrid>""" +
            """<w:tr><w:tc><w:tcPr><w:tcW w:w="4531" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>Zelle 1</w:t></w:r></w:p></w:tc>""" +
            """<w:tc><w:p><w:r><w:t>Zelle 2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
            """<w:sdt><w:sdtPr><w:rPr><w:b/></w:rPr><w:alias w:val="Feld"/><w:showingPlcHdr/></w:sdtPr><w:sdtContent><w:p><w:r><w:t>Steuerelement</w:t></w:r></w:p></w:sdtContent></w:sdt>"""
        assertOutline(read(body), "Paragraph: Link", "Paragraph: Zelle 1", "Paragraph: Zelle 2", "Paragraph: Steuerelement")
    }

    @Test
    fun textBoxesBecomeOwnParagraphsWithoutDuplicates() {
        val textBox = """<w:txbxContent><w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Im Kasten</w:t></w:r></w:p></w:txbxContent>"""
        val body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>""" +
            """<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Vorher </w:t></w:r>""" +
            """<w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wp:anchor><wps:wsp><wps:txbx>$textBox</wps:txbx></wps:wsp></wp:anchor></w:drawing></mc:Choice>""" +
            """<mc:Fallback><w:pict><v:shape><v:textbox>$textBox</v:textbox></v:shape></w:pict></mc:Fallback></mc:AlternateContent></w:r>""" +
            """<w:r><w:rPr><w:b/></w:rPr><w:t>nachher</w:t></w:r></w:p>"""
        val document = read(body)
        assertOutline(document, "ListItem •: Vorher", "Heading2: Im Kasten", "ListItem: nachher")
        assertEquals("**nachher**", document.blocks.last().markup())
    }

    @Test
    fun strictOoxmlNamespaceIsSupported() {
        val strict = TestDocuments.W_NAMESPACES.replace(
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "http://purl.oclc.org/ooxml/wordprocessingml/main",
        )
        val xml = TestDocuments.docxXml("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:i w:val="on"/></w:rPr><w:t>Strikt</w:t></w:r></w:p>""", strict)
        val file = TestDocuments.zip(folder.newFile("strict.docx"), "word/document.xml" to xml.toByteArray())
        val block = DocumentText.read(file, DocumentKind.Docx).blocks.single()
        assertEquals(BlockStyle.Heading1, block.style)
        assertEquals("*Strikt*", block.markup())
    }

    @Test
    fun mainPartIsFoundThroughRelationships() {
        val rels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="/word/document2.xml"/></Relationships>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Override PartName="/word/document2.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val file = TestDocuments.zip(
            folder.newFile("export.docx"),
            "[Content_Types].xml" to contentTypes.toByteArray(),
            "_rels/.rels" to rels.toByteArray(),
            "word/document2.xml" to TestDocuments.docxXml(p("Anderer Name")).toByteArray(),
        )
        assertEquals(DocumentKind.Docx, DocumentDetector.detect(file, "export.docx", null))
        assertEquals("Anderer Name", DocumentText.read(file, DocumentKind.Docx).plainText)
    }

    @Test
    fun damagedDocumentsThrowIOExceptionWithGermanMessage() {
        val brokenXml = TestDocuments.zip(folder.newFile("a.docx"), "word/document.xml" to "<w:document><w:body><w:p>kein XML".toByteArray())
        val noDocument = TestDocuments.zip(folder.newFile("b.docx"), "word/other.xml" to "<x/>".toByteArray())
        val wrongRoot = TestDocuments.zip(folder.newFile("c.docx"), "word/document.xml" to "<?xml version=\"1.0\"?><html/>".toByteArray())
        val notZip = folder.newFile("d.docx").apply { writeText("PK das ist kein ZIP") }
        val doctype = TestDocuments.zip(
            folder.newFile("e.docx"),
            "word/document.xml" to """<?xml version="1.0"?><!DOCTYPE w:document [<!ENTITY a "aaaaaaaaaa">]><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>&a;</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
        )
        for (file in listOf(brokenXml, noDocument, wrongRoot, notZip, doctype)) {
            val error = assertThrows(file.name, IOException::class.java) { DocumentText.read(file, DocumentKind.Docx) }
            assertEquals(file.name, "Die Datei ist beschädigt oder kein gültiges Word-Dokument.", error.message)
        }
    }

    @Test
    fun brokenStylesXmlIsIgnored() {
        val file = TestDocuments.zip(
            folder.newFile("styles.docx"),
            "word/document.xml" to TestDocuments.docxXml(p("Kapitel", "Heading1")).toByteArray(),
            "word/styles.xml" to "<w:styles kaputt".toByteArray(),
        )
        assertOutline(DocumentText.read(file, DocumentKind.Docx), "Heading1: Kapitel")
    }

    @Test
    fun zipBombIsStoppedByTheSizeLimit() {
        // Viele leere Absätze: komprimiert winzig, entpackt groß, und der Textspeicher wird nie voll.
        val file = TestDocuments.docx(folder.newFile("bomb.docx"), "<w:p/>".repeat(200_000))
        assertTrue(file.length() < 100_000)
        val error = assertThrows(IOException::class.java) { DocxReader.read(file, TextDocumentBuilder(), maxBytes = 500_000) }
        assertTrue(error.message!!, error.message!!.contains("zu groß"))
        // Mit der normalen Grenze klappt es.
        assertTrue(DocumentText.read(file, DocumentKind.Docx).blocks.isEmpty())
    }

    @Test
    fun longDocumentsAreTruncated() {
        val document = read((1..100).joinToString("") { p("Absatz Nummer $it mit etwas Text.") }, maxChars = 500)
        assertTrue(document.truncated)
        assertTrue(document.blocks.sumOf { it.text.length } in 400..500)
        assertEquals("Absatz Nummer 1 mit etwas Text.", document.blocks.first().text)
    }

    @Test
    fun readsFileWrittenByWordWithStylesXml() {
        val file: File = TestDocuments.docx(
            folder.newFile("word.docx"),
            p("Der Nordwind und die Sonne", "berschrift1") +
                """<w:p><w:r><w:t xml:space="preserve">Einst stritten sich Nordwind und Sonne, wer von ihnen beiden wohl der Stärkere wäre, </w:t></w:r>""" +
                """<w:r><w:rPr><w:i/></w:rPr><w:t>als ein Wanderer</w:t></w:r><w:r><w:t>, der in einen warmen Mantel gehüllt war, des Weges daherkam.</w:t></w:r></w:p>""",
            headingStyles,
        )
        assertEquals(DocumentKind.Docx, DocumentDetector.detect(file, "Nordwind.zip", "application/zip"))
        val document = DocumentText.read(file, DocumentKind.Docx)
        assertEquals(BlockStyle.Heading1, document.blocks[0].style)
        assertTrue(document.blocks[1].markup().contains("*als ein Wanderer*"))
        assertTrue(!document.truncated)
    }
}
