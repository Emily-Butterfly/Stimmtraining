package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = FileStore(context)

    @Test
    fun everyHandoffGetsItsOwnCopyAndOldOnesAreCleanedUp() {
        val audio = files.newDraftFile("m4a").apply { writeBytes(ByteArray(10)) }
        val first = files.handoffCopy(FileStore.SHARE_DIR, audio, "Stimmtraining_2026-11-07")
        val second = files.handoffCopy(FileStore.SHARE_DIR, audio, "Stimmtraining_2026-11-07")
        assertEquals("Stimmtraining_2026-11-07.m4a", first.name)
        assertNotEquals("eigene Adresse für jede Kopie", first, second)
        assertTrue("eine App, die die erste noch liest, verliert sie nicht", first.isFile && second.isFile)

        // Kopien von gestern werden beim nächsten Weitergeben aufgeräumt.
        first.parentFile!!.setLastModified(System.currentTimeMillis() - FileStore.HANDOFF_KEEP_MS - 60_000)
        files.handoffCopy(FileStore.SHARE_DIR, audio, "Noch eine")
        assertFalse(first.exists())
        assertTrue(second.exists())
        File(context.cacheDir, FileStore.SHARE_DIR).deleteRecursively()
    }

    @Test
    fun audioIsSharedWithTheTypeOfItsExtension() {
        assertEquals("audio/mp4", FileStore.audioMimeType("m4a"))
        assertEquals("audio/ogg", FileStore.audioMimeType("OGG"))
        assertEquals("audio/mpeg", FileStore.audioMimeType("mp3"))
        assertEquals("audio/*", FileStore.audioMimeType("xyz"))
    }
}
