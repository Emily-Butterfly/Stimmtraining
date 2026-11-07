package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import de.emilybutterfly.stimmtraining.data.library.TextLayout
import de.emilybutterfly.stimmtraining.data.library.TextProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c2" href="Text/kapitel%202.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="Text/kapitel1.xhtml" media-type="application/xhtml+xml"/>
            <item id="notes" href="Text/notes.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
            <itemref idref="cover"/>
            <itemref idref="c2"/>
            <itemref idref="notes" linear="no"/>
          </spine>
        </package>"""

    private fun chapter(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Nicht lesen</title><style>p { }</style></head>
        <body>$body</body></html>""".toByteArray()

    private fun book(name: String = "buch.epub", vararg extra: Pair<String, ByteArray>): File = TestDocuments.zip(
        File(folder.root, name),
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to container.toByteArray(),
        "OEBPS/content.opf" to opf.toByteArray(),
        "OEBPS/nav.xhtml" to chapter("<nav><ol><li>Inhalt</li></ol></nav>"),
        "OEBPS/Text/kapitel1.xhtml" to chapter("<h1>Erstes Kapitel</h1><p>Es war <i>einmal</i> ein Satz.</p><p>Und noch einer"),
        "OEBPS/Text/kapitel 2.xhtml" to chapter("<h2>Zweites Kapitel</h2><p>Der <b>zweite</b> Teil.</p>"),
        "OEBPS/Text/notes.xhtml" to chapter("<p>Fußnote</p>"),
        *extra,
    )

    @Test
    fun chaptersAreReadInSpineOrderWithFormatting() {
        val file = book()
        assertEquals(DocumentKind.Epub, DocumentDetector.detect(file, "buch.epub", null))
        val document = DocumentText.read(file, DocumentKind.Epub)
        assertEquals(
            listOf("Erstes Kapitel", "Es war einmal ein Satz.", "Und noch einer", "Zweites Kapitel", "Der zweite Teil."),
            document.blocks.map { it.text },
        )
        assertEquals(BlockStyle.Heading1, document.blocks[0].style)
        assertTrue(document.blocks[1].spans.any { it.italic && it.text == "einmal" })
        assertTrue(document.blocks[4].spans.any { it.bold && it.text == "zweite" })
        // Inhaltsverzeichnis, Titel, Stil und Fußnoten (linear="no") gehören nicht zum Lesetext.
        assertTrue(document.plainText.contains("Inhalt").not() && !document.plainText.contains("Fußnote"))
    }

    @Test
    fun readingProgressWorksLikeForOtherTexts() {
        val layout = TextLayout(DocumentText.read(book(), DocumentKind.Epub))
        val first = layout.sentenceAt(layout.plainText.indexOf("Es war"))!!
        val after = TextProgress.after(layout, first)
        val rest = TextProgress.remainingStart(layout, after.offset)
        assertTrue(layout.plainText.substring(rest).startsWith("Und noch einer"))
    }

    @Test
    fun withoutPackageDocumentChaptersAreReadByName() {
        val file = TestDocuments.zip(
            File(folder.root, "lose.epub"),
            "mimetype" to "application/epub+zip".toByteArray(),
            "b.xhtml" to chapter("<p>Zwei.</p>"),
            "a.xhtml" to chapter("<p>Eins.</p>"),
        )
        assertEquals(listOf("Eins.", "Zwei."), DocumentText.read(file, DocumentKind.Epub).blocks.map { it.text })
    }

    @Test
    fun drmProtectedAndImageOnlyBooksGetAClearMessage() {
        val encryption = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:CipherData><enc:CipherReference URI="OEBPS/Text/kapitel1.xhtml"/></enc:CipherData></enc:EncryptedData>
            </encryption>"""
        val drm = book("drm.epub", "META-INF/encryption.xml" to encryption.toByteArray())
        val message = assertThrows(UnsupportedDocumentException::class.java) { DocumentDetector.detect(drm, "drm.epub", null) }.message!!
        assertTrue(message, message.contains("kopiergeschützt"))

        // Nur verschleierte Schriften: kein Kopierschutz für den Text.
        val fonts = encryption.replace("OEBPS/Text/kapitel1.xhtml", "OEBPS/Fonts/schrift.otf")
        assertEquals(DocumentKind.Epub, DocumentDetector.detect(book("schrift.epub", "META-INF/encryption.xml" to fonts.toByteArray()), "schrift.epub", null))

        val pictures = TestDocuments.zip(
            File(folder.root, "comic.epub"),
            "mimetype" to "application/epub+zip".toByteArray(),
            "seite.xhtml" to chapter("<img src=\"seite.jpg\"/>"),
        )
        val error = assertThrows(java.io.IOException::class.java) { DocumentText.read(pictures, DocumentKind.Epub) }
        assertEquals(EpubReader.NO_TEXT, error.message)
    }

    @Test
    fun linksCannotLeaveTheBook() {
        assertEquals("OEBPS/Text/a b.xhtml", EpubReader.resolve("OEBPS", "Text/a%20b.xhtml#x"))
        assertEquals("Text/a.xhtml", EpubReader.resolve("OEBPS", "../Text/a.xhtml"))
        assertNull(EpubReader.resolve("OEBPS", "../../etc/passwd"))
        assertNull(EpubReader.resolve("OEBPS", "http://example.com/a.xhtml"))
    }
}
