package de.emilybutterfly.stimmtraining.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.emilybutterfly.stimmtraining.data.document.DocumentReadException
import de.emilybutterfly.stimmtraining.data.document.UnsupportedDocumentException
import de.emilybutterfly.stimmtraining.data.takeWhole
import de.emilybutterfly.stimmtraining.ui.components.limitedEdit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class ImportAndEditorTest {
    @Test
    fun importMessagesAreGermanAndNeverRawSystemText() {
        assertEquals(
            "Ältere Word-Dateien gehen nicht.",
            importFailureMessage(isPhoto = false, UnsupportedDocumentException("Ältere Word-Dateien gehen nicht.")),
        )
        assertEquals(
            "Die Datei ist zu groß.",
            importFailureMessage(false, RuntimeException("Wrapper", DocumentReadException("Die Datei ist zu groß."))),
        )
        assertEquals(
            "Die Datei konnte nicht geöffnet werden.",
            importFailureMessage(false, FileNotFoundException("open failed: ENOENT (No such file or directory)")),
        )
        assertEquals("Die Datei konnte nicht übernommen werden.", importFailureMessage(false, IOException("write failed: ENOSPC")))
        assertEquals("Das Foto konnte nicht übernommen werden.", importFailureMessage(true, IOException("read failed: EIO")))
    }

    @Test
    fun overLongInsertionIsShortenedNotTheExistingEnd() {
        val old = TextFieldValue("Anfang Ende", TextRange(6))
        // An Position 6 werden 5 Zeichen eingefügt; erlaubt sind 13 Zeichen, also 3 zu viel.
        val new = TextFieldValue("Anfang12345 Ende", TextRange(11))
        val limited = limitedEdit(old, new, max = 13)
        assertEquals("Anfang12 Ende", limited.text)
        assertEquals(TextRange(8), limited.selection)
    }

    @Test
    fun anEmojiIsNeverCutInHalf() {
        val old = TextFieldValue("abc", TextRange(3))
        val withEmoji = TextFieldValue("abc\uD83D\uDE00", TextRange(5)) // 😀 = zwei Zeichen
        assertEquals(old, limitedEdit(old, withEmoji, max = 4))
        assertEquals("ab", "ab\uD83D\uDE00".takeWhole(3))
        assertEquals("ab\uD83D\uDE00", "ab\uD83D\uDE00".takeWhole(4))
    }

    @Test
    fun editsWithinTheLimitStayAndImpossibleOnesKeepTheOldText() {
        val old = TextFieldValue("abc", TextRange(3))
        val ok = TextFieldValue("abcd", TextRange(4))
        assertEquals(ok, limitedEdit(old, ok, max = 4))
        // Ersetzen einer Auswahl, das trotzdem zu lang wird, ohne eingefügten Teil vor dem Cursor: alter Stand.
        val weird = TextFieldValue("abcdefgh", TextRange(0, 8))
        assertEquals(old, limitedEdit(old, weird, max = 4))
    }
}
