package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PastedTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nameIsTheFirstNonEmptyLineShortened() {
        assertEquals("Der Nordwind und die Sonne", PastedText.name("\n  Der Nordwind und   die Sonne  \nEinst stritten …"))
        assertEquals("Eingefügter Text", PastedText.name(" \n\t\n"))
        val long = PastedText.name("Einst stritten sich Nordwind und Sonne, wer von ihnen beiden wohl der Stärkere wäre")
        assertEquals(40, long.length)
        assertEquals('…', long.last())
    }

    @Test
    fun draftIsUtf8AndCanBeEditedAgain() {
        val text = "Größe, Übung, Äußerung – „Zitat“\nzweite Zeile"
        val draft = PastedText.createDraft(FileStore(context), text)
        assertEquals("txt", draft.extension)
        assertEquals(text, PastedText.readForEditing(draft))
    }

    @Test
    fun filesThatWouldBeDamagedByEditingAreNotEditable() {
        val dir = File(context.cacheDir, "texts").apply { mkdirs() }
        val latin1 = File(dir, "alt.txt").apply { writeBytes("Größe".toByteArray(Charsets.ISO_8859_1)) }
        assertNull("kein UTF-8", PastedText.readForEditing(latin1))
        val huge = File(dir, "lang.txt").apply { writeText("a".repeat(PastedText.MAX_CHARS + 1)) }
        assertNull("zu lang", PastedText.readForEditing(huge))
        val withBom = File(dir, "bom.txt").apply { writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hallo".toByteArray()) }
        assertEquals("Hallo", PastedText.readForEditing(withBom))
    }
}
