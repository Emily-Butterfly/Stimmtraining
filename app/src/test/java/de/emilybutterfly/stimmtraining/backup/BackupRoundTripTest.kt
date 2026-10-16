package de.emilybutterfly.stimmtraining.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.data.AppDatabase
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.data.RecordingRepository
import de.emilybutterfly.stimmtraining.data.TextAttachment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sichert Aufnahmen, „wechselt das Gerät“ und stellt sie wieder her. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var files: FileStore
    private lateinit var repository: RecordingRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() = openFreshDevice()

    @After
    fun tearDown() = db.close()

    private fun openFreshDevice() {
        if (::db.isInitialized) db.close()
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        files = FileStore(context)
        repository = RecordingRepository(db.recordingDao(), files)
        manager = BackupManager(context, db.recordingDao(), files)
    }

    private fun draft(extension: String, bytes: ByteArray) =
        files.newDraftFile(extension).apply { writeBytes(bytes) }

    @Test
    fun exportAndImportRestoresEverything() = runBlocking {
        val audioA = ByteArray(5000) { (it % 251).toByte() }
        val audioB = ByteArray(1234) { 7 }
        val photo = ByteArray(800) { (it * 3).toByte() }
        repository.create(
            draftAudio = draft("m4a", audioA),
            draftAttachment = TextAttachment.Photo(draft("jpg", photo)),
            recordedAt = 1_791_000_000_000,
            durationMs = 61_000,
            params = RecordingParams(
                title = "Nordwind",
                exercise = "Text lesen",
                warmedUp = true,
                warmUpMinutes = "15",
                voiceCondition = 4,
                selfRating = 5,
                notes = "gut",
            ),
        )
        repository.create(draft("m4a", audioB), null, 1_791_100_000_000, 12_000, RecordingParams())
        val document = "Der Nordwind und die Sonne stritten sich.".toByteArray()
        repository.create(
            draft("m4a", audioB),
            TextAttachment.Document(draft("txt", document), "Nordwind und Sonne.txt"),
            1_791_200_000_000,
            15_000,
            RecordingParams(exercise = "Text lesen"),
        )
        val before = db.recordingDao().getAll()

        val zip = ByteArrayOutputStream()
        assertEquals(3, manager.export(zip))

        openFreshDevice()
        val result = manager.import(ByteArrayInputStream(zip.toByteArray()))
        assertEquals(ImportResult(imported = 3, skipped = 0, incomplete = 0), result)

        val after = db.recordingDao().getAll()
        assertEquals(before.map(::comparable), after.map(::comparable))
        assertArrayEquals(audioA, files.audio(after[0].audioFile).readBytes())
        assertArrayEquals(photo, files.photo(assertNotNull(after[0].photoFile)).readBytes())
        assertArrayEquals(audioB, files.audio(after[1].audioFile).readBytes())
        assertNull(after[1].photoFile)
        assertNull(after[1].documentFile)
        assertEquals("Nordwind und Sonne.txt", after[2].documentName)
        assertArrayEquals(document, files.document(assertNotNull(after[2].documentFile)).readBytes())

        // Ein zweites Einspielen derselben Sicherung legt nichts doppelt an.
        val again = manager.import(ByteArrayInputStream(zip.toByteArray()))
        assertEquals(ImportResult(imported = 0, skipped = 3, incomplete = 0), again)
        assertEquals(3, db.recordingDao().getAll().size)
    }

    @Test
    fun importRejectsZipWithoutManifest() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("audio/x.m4a"))
            it.write(1)
            it.closeEntry()
        }
        assertThrows(BackupException::class.java) {
            runBlocking { manager.import(ByteArrayInputStream(zip.toByteArray())) }
        }
        assertEquals(0, runBlocking { db.recordingDao().getAll().size })
    }

    @Test
    fun deleteRemovesFiles() = runBlocking {
        val id = repository.create(draft("m4a", byteArrayOf(1, 2, 3)), TextAttachment.Photo(draft("jpg", byteArrayOf(4))), 1L, 1L, RecordingParams())
        val recording = assertNotNull(db.recordingDao().get(id))
        repository.delete(recording)
        assertEquals(false, files.audio(recording.audioFile).exists())
        assertEquals(false, files.photo(assertNotNull(recording.photoFile)).exists())
    }

    /** Alles außer den gerätespezifischen Werten (ID, Dateinamen). */
    private fun comparable(r: Recording) =
        r.copy(id = 0, audioFile = "", photoFile = r.photoFile?.let { "photo" }, documentFile = r.documentFile?.let { "document" })

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull(null as String?, value)
        return value!!
    }
}
