package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocumentDetectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "detect").apply { mkdirs() }
    private var counter = 0

    private fun file(bytes: ByteArray) = File(dir, "f${counter++}").apply { writeBytes(bytes) }

    private fun detect(bytes: ByteArray, name: String? = null, mime: String? = null) =
        DocumentDetector.detect(file(bytes), name, mime)

    private fun rejected(bytes: ByteArray, name: String? = null): String =
        assertThrows(UnsupportedDocumentException::class.java) { detect(bytes, name) }.message.orEmpty()

    @Test
    fun contentBeatsNameAndMimeType() {
        assertEquals(DocumentKind.Pdf, detect("%PDF-1.7\n…".toByteArray(), "Text.txt", "text/plain"))
        assertEquals(DocumentKind.Rtf, detect("{\\rtf1\\ansi Hallo}".toByteArray(), "Brief.doc"))
        val docx = TestDocuments.docx(File(dir, "a.zip"), "<w:p><w:r><w:t>Hallo</w:t></w:r></w:p>")
        assertEquals(DocumentKind.Docx, DocumentDetector.detect(docx, "Archiv.zip", "application/zip"))
        val odt = TestDocuments.odt(File(dir, "b"), "<text:p>Hallo</text:p>")
        assertEquals(DocumentKind.Odt, DocumentDetector.detect(odt, null, "application/octet-stream"))
    }

    @Test
    fun textFormatsByNameMimeTypeOrContent() {
        val text = "# Titel\n\nText".toByteArray()
        assertEquals(DocumentKind.Markdown, detect(text, "Lesetext.md", "application/octet-stream"))
        assertEquals(DocumentKind.Markdown, detect(text, "Lesetext", "text/x-markdown"))
        assertEquals(DocumentKind.PlainText, detect(text, "Lesetext.txt", "text/plain"))
        assertEquals(DocumentKind.Html, detect("<p>Hallo</p>".toByteArray(), "Seite.htm"))
        assertEquals(DocumentKind.Html, detect("﻿  <!DOCTYPE html><html><body>Hi".toByteArray(), "download"))
        assertEquals(DocumentKind.Html, detect("<?xml version=\"1.0\"?>\n<html xmlns=\"x\">".toByteArray()))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hallo".toByteArray(Charsets.UTF_16LE)
        assertEquals("UTF-16 enthält Nullbytes und ist trotzdem Text", DocumentKind.PlainText, detect(utf16, "x.txt"))
    }

    @Test
    fun unsupportedFormatsGetHelpfulMessages() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(100)
        assertTrue(rejected(ole, "Alt.doc").contains(".docx"))
        assertTrue(rejected(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2), "Foto.jpg").contains("Galerie"))
        assertTrue(rejected(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10)).contains("Galerie"))
        assertTrue(rejected(byteArrayOf(1, 2, 0, 3, 4, 0, 5), "programm.bin").contains("Unterstützt"))
        assertEquals("Die Datei ist leer.", rejected(ByteArray(0), "leer.txt"))
        assertTrue(rejected("PK\u0003\u0004 kaputt".toByteArray(), "x.docx").contains("beschädigt"))

        val ods = TestDocuments.zip(File(dir, "t.ods"), "mimetype" to "application/vnd.oasis.opendocument.spreadsheet".toByteArray(), "content.xml" to "<x/>".toByteArray())
        assertTrue(assertThrows(UnsupportedDocumentException::class.java) { DocumentDetector.detect(ods, "t.ods", null) }.message!!.contains("Tabellen"))
        val epub = TestDocuments.zip(File(dir, "b.epub"), "mimetype" to "application/epub+zip".toByteArray())
        assertTrue(assertThrows(UnsupportedDocumentException::class.java) { DocumentDetector.detect(epub, "b.epub", null) }.message!!.contains("EPUB"))
        val plainZip = TestDocuments.zip(File(dir, "a.zip"), "liesmich.txt" to "Hallo".toByteArray())
        assertTrue(assertThrows(UnsupportedDocumentException::class.java) { DocumentDetector.detect(plainZip, "a.zip", null) }.message!!.contains("Entpacke"))
    }
}
