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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /**
     * Kleinster OLE-Container: Kopf, FAT in Sektor 0, Verzeichnis ab Sektor 1 mit vier Einträgen
     * je Sektor, über die FAT verkettet.
     */
    private fun ole(vararg streams: String): ByteArray {
        val names = listOf("Root Entry") + streams
        val directorySectors = (names.size + 3) / 4
        val bytes = ByteArray(512 * (2 + directorySectors))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).forEachIndexed { i, b -> bytes[i] = b.toByte() }
        buffer.putShort(0x18, 0x3E).putShort(0x1A, 3).putShort(0x1C, 0xFFFE.toShort()).putShort(0x1E, 9).putShort(0x20, 6)
        buffer.putInt(0x2C, 1).putInt(0x30, 1).putInt(0x3C, -2).putInt(0x44, -2)
        for (k in 0 until 109) buffer.putInt(0x4C + 4 * k, if (k == 0) 0 else -1)
        for (k in 0 until 128) buffer.putInt(512 + 4 * k, -1)
        buffer.putInt(512, -3)
        for (sector in 1..directorySectors) buffer.putInt(512 + 4 * sector, if (sector < directorySectors) sector + 1 else -2)
        names.forEachIndexed { index, name ->
            val offset = 1024 + 128 * index
            name.toByteArray(Charsets.UTF_16LE).copyInto(bytes, offset)
            buffer.putShort(offset + 0x40, ((name.length + 1) * 2).toShort())
            bytes[offset + 0x42] = (if (index == 0) 5 else 2).toByte()
        }
        return bytes
    }

    private fun ByteBuffer.putShort(index: Int, value: Int): ByteBuffer = putShort(index, value.toShort())

    @Test
    fun passwordProtectedFilesGetTheirOwnMessage() {
        val password = "Die Datei ist mit einem Kennwort geschützt. Speichere sie ohne Kennwort und wähle sie dann erneut aus."
        // Word, Excel und PowerPoint verschlüsseln .docx & Co. in einem OLE-Container; der Eintrag
        // "EncryptedPackage" steht hier im zweiten Verzeichnissektor.
        val encrypted = ole("DataSpaces", "EncryptionInfo", "Version", "EncryptedPackage")
        assertEquals(password, rejected(encrypted, "Brief.docx"))
        assertEquals(password, rejected(encrypted, "Brief.doc"))
        assertEquals(password, rejected(encrypted))
        val header = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(100)
        assertEquals(password, rejected(header, "Tabelle.xlsx"))

        val oldWord = ole("WordDocument", "1Table", "SummaryInformation", "DocumentSummaryInformation", "CompObj")
        assertTrue(rejected(oldWord, "Alt.doc").contains(".docx"))
        assertTrue(rejected(oldWord, "Alt.xls").startsWith("Ältere Office-Dateien"))

        // LibreOffice: ein normales ZIP, aber content.xml ist verschlüsselt.
        val manifest = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3"><manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml" manifest:size="2214"><manifest:encryption-data manifest:checksum-type="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha256-1k" manifest:checksum="kFZ9rXvb"><manifest:algorithm manifest:algorithm-name="http://www.w3.org/2001/04/xmlenc#aes256-cbc" manifest:initialisation-vector="b2bJ7Q=="/><manifest:start-key-generation manifest:start-key-generation-name="http://www.w3.org/2000/09/xmldsig#sha256" manifest:key-size="32"/><manifest:key-derivation manifest:key-derivation-name="PBKDF2" manifest:key-size="32" manifest:iteration-count="100000" manifest:salt="Zm9vYmFy"/></manifest:encryption-data></manifest:file-entry></manifest:manifest>"""
        val odt = TestDocuments.zip(
            File(dir, "geheim.odt"),
            "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
            "content.xml" to ByteArray(256) { (it * 37).toByte() },
            "META-INF/manifest.xml" to manifest.toByteArray(),
        )
        assertEquals(password, assertThrows(UnsupportedDocumentException::class.java) { DocumentDetector.detect(odt, "geheim.odt", null) }.message)
    }
}
