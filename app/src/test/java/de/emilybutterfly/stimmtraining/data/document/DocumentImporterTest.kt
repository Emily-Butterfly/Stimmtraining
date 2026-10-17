package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.data.FileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocumentImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var files: FileStore
    private val drafts get() = File(context.filesDir, "drafts").listFiles().orEmpty().toList()

    @Before
    fun setUp() {
        context.filesDir.deleteRecursively()
        files = FileStore(context)
    }

    /** Eine Datei, wie sie die Dateiauswahl liefern würde. */
    private fun picked(name: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "picked").apply { mkdirs() }
        return Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })
    }

    private fun import(uri: Uri, maxBytes: Long = DocumentImporter.MAX_BYTES) =
        DocumentImporter.import(context.contentResolver, files, uri, maxBytes)

    @Test
    fun textFileBecomesADraftWithItsOriginalName() {
        val document = import(picked("Nordwind und Sonne.md", "# Der Nordwind\n\nEinst stritten sich …".toByteArray()))

        assertEquals("Nordwind und Sonne.md", document.name)
        assertEquals(DocumentKind.Markdown, document.kind)
        assertEquals("Der Nordwind", DocumentText.read(document.file, document.kind).blocks.first().text)
        assertEquals(listOf(document.file), drafts)
    }

    @Test
    fun pdfIsRecognisedByItsContentEvenWithoutExtension() {
        val document = import(picked("Scan vom Text", "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\n".toByteArray()))

        assertEquals(DocumentKind.Pdf, document.kind)
        assertEquals("pdf", document.file.extension)
        assertEquals("Scan vom Text.pdf", document.name)
    }

    @Test
    fun unsupportedFilesAreRejectedAndLeaveNothingBehind() {
        val oldWord = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) +
            ByteArray(512)
        val error = assertThrows(UnsupportedDocumentException::class.java) { import(picked("Text.doc", oldWord)) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(".doc"))
        assertEquals(emptyList<File>(), drafts)
    }

    @Test
    fun tooLargeFilesAreRejected() {
        val error = assertThrows(IOException::class.java) { import(picked("lang.txt", ByteArray(2048) { 'a'.code.toByte() }), maxBytes = 1024) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("zu groß"))
        assertEquals(emptyList<File>(), drafts)
    }

    @Test
    fun cancelledImportStopsAndLeavesNothingBehind() {
        assertThrows(IOException::class.java) {
            DocumentImporter.import(context.contentResolver, files, picked("Text.txt", ByteArray(100_000)), isCancelled = { true })
        }
        assertEquals(emptyList<File>(), drafts)
    }

    @Test
    fun documentsOpenInOtherAppsUnderTheirOwnName() {
        val document = import(picked("Nordwind und Sonne.md", "# Titel".toByteArray()))
        val intent = files.viewIntent(document.file, document.name, document.kind.mimeType)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals("text/markdown", intent.type)
        assertTrue(intent.data.toString(), intent.data.toString().endsWith("/open/Nordwind%20und%20Sonne.md"))
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun displayNamesGetAnExtensionAndLoseControlCharacters() {
        assertEquals("Text.txt", DocumentImporter.documentName(null, DocumentKind.PlainText))
        assertEquals("Gedicht.odt", DocumentImporter.documentName("Gedicht", DocumentKind.Odt))
        assertEquals("Zeile zwei.pdf", DocumentImporter.documentName("Zeile\u0000 zwei.pdf", DocumentKind.Pdf))
    }
}
