package de.emilybutterfly.stimmtraining.data.document

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Textdateien in den Kodierungen, die auf deutschen Geräten vorkommen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlainTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "plain").apply { mkdirs() }

    private fun read(bytes: ByteArray, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS) =
        DocumentText.read(File(dir, "t.txt").apply { writeBytes(bytes) }, DocumentKind.PlainText, maxChars)

    private val poem = "Der Nordwind\n\nEinst stritten sich\r\nNordwind und Sonne,\rwer stärker wäre.\n"

    @Test
    fun paragraphsAndLineBreaksArePreserved() {
        val document = read(poem.toByteArray())
        assertEquals(listOf("Der Nordwind", "Einst stritten sich\nNordwind und Sonne,\nwer stärker wäre."), document.blocks.map { it.text })
    }

    @Test
    fun encodingsAreRecognised() {
        val expected = read(poem.toByteArray()).plainText
        assertEquals("Windows-1252", expected, read(poem.toByteArray(TextDecoding.WINDOWS_1252)).plainText)
        assertEquals("UTF-8 mit BOM", expected, read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + poem.toByteArray()).plainText)
        assertEquals("UTF-16 LE", expected, read(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + poem.toByteArray(Charsets.UTF_16LE)).plainText)
        assertEquals("UTF-16 BE", expected, read(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + poem.toByteArray(Charsets.UTF_16BE)).plainText)
    }

    @Test
    fun controlCharactersAndTabs() {
        assertEquals("Spalte\tzwei\nnach Seitenumbruch", read("Spalte\tzwei\u000Cnach Seitenumbruch\u0007".toByteArray()).plainText)
    }

    @Test
    fun longTextIsTruncated() {
        val document = read("Wort ".repeat(10_000).toByteArray(), maxChars = 1_000)
        assertTrue(document.truncated)
        assertTrue(document.plainText.length <= 1_000)
        assertFalse(read(poem.toByteArray()).truncated)
    }
}
