package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.analysis.PitchResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var files: FileStore
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        context.filesDir.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        files = FileStore(context)
        repository = RecordingRepository(db.recordingDao(), files)
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    @Test
    fun failedSaveLeavesTheDraftsInPlaceSoSavingCanBeRetried() = runBlocking {
        val audio = files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val photo = files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(4, 5)) }
        db.close() // jede Datenbankoperation schlägt jetzt fehl

        try {
            repository.create(audio, TextAttachment.Photo(photo), 1L, 1L, RecordingParams())
            fail("Speichern hätte fehlschlagen müssen")
        } catch (_: Exception) {
        }

        assertTrue("Audio-Entwurf ist wieder da", audio.isFile)
        assertTrue("Foto-Entwurf ist wieder da", photo.isFile)
        assertEquals(0, files.audioDir.listFiles()?.size ?: 0)
        assertEquals(0, files.photoDir.listFiles()?.size ?: 0)
    }

    @Test
    fun failedSaveAlsoKeepsADocumentDraft() = runBlocking {
        val audio = files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) }
        val document = files.newDraftFile("pdf").apply { writeBytes(byteArrayOf(2)) }
        db.close()

        runCatching { repository.create(audio, TextAttachment.Document(document, "Nordwind.pdf"), 1L, 1L, RecordingParams()) }

        assertTrue("Audio-Entwurf ist wieder da", audio.isFile)
        assertTrue("Dokument-Entwurf ist wieder da", document.isFile)
        assertEquals(0, files.documentDir.listFiles()?.size ?: 0)
    }

    @Test
    fun documentCanBeSavedSwappedForAPhotoAndRemoved() = runBlocking {
        val draft = files.newDraftFile("pdf").apply { writeBytes(byteArrayOf(7, 7)) }
        val id = repository.create(
            files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) },
            TextAttachment.Document(draft, "Nordwind und Sonne.pdf"),
            1L,
            1L,
            RecordingParams(),
        )
        val saved = db.recordingDao().get(id)!!
        val document = files.document(saved.documentFile!!)
        assertNull(saved.photoFile)
        assertEquals("pdf", document.extension)
        assertTrue(document.readBytes().contentEquals(byteArrayOf(7, 7)))
        assertEquals(TextAttachment.Document(document, "Nordwind und Sonne.pdf"), repository.attachmentOf(saved))

        // Ein Foto ersetzt das Dokument; die alte Datei verschwindet.
        val photo = files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(3)) }
        repository.update(id, saved.params(), AttachmentChange.Replace(TextAttachment.Photo(photo)))
        val withPhoto = db.recordingDao().get(id)!!
        assertNull(withPhoto.documentFile)
        assertNull(withPhoto.documentName)
        assertTrue(files.photo(withPhoto.photoFile!!).isFile)
        assertFalse("altes Dokument gelöscht", document.exists())

        // Und wieder zurück zu einer Datei.
        val text = files.newDraftFile("txt").apply { writeText("Der Nordwind") }
        repository.update(id, saved.params(), AttachmentChange.Replace(TextAttachment.Document(text, "Nordwind.txt")))
        val withText = db.recordingDao().get(id)!!
        assertNull(withText.photoFile)
        assertFalse("altes Foto gelöscht", files.photo(withPhoto.photoFile!!).exists())
        assertEquals("Nordwind.txt", withText.documentName)
        assertEquals("Der Nordwind", files.document(withText.documentFile!!).readText())

        repository.update(id, saved.params(), AttachmentChange.Remove)
        val without = db.recordingDao().get(id)!!
        assertNull(without.documentFile)
        assertNull(repository.attachmentOf(without))
        assertEquals(0, files.documentDir.listFiles()?.size ?: 0)
    }

    @Test
    fun deletingARecordingDeletesItsDocument() = runBlocking {
        val id = repository.create(
            files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) },
            TextAttachment.Document(files.newDraftFile("docx").apply { writeBytes(byteArrayOf(2)) }, "Text.docx"),
            1L,
            1L,
            RecordingParams(),
        )
        repository.delete(db.recordingDao().get(id)!!)
        assertEquals(0, files.documentDir.listFiles()?.size ?: 0)
        assertEquals(0, files.audioDir.listFiles()?.size ?: 0)
    }

    @Test
    fun pitchResultIsStoredAndKeptWhenEditing() = runBlocking {
        val id = repository.create(files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) }, null, 1L, 1L, RecordingParams())
        val before = db.recordingDao().get(id)!!

        repository.savePitch(id, PitchResult(mostFrequent = 57, lowest = 52, highest = 62, voicedMs = 8_000))
        val analysed = db.recordingDao().get(id)!!
        assertEquals(57, analysed.pitchMostFrequent)
        assertEquals(52, analysed.pitchLowest)
        assertEquals(62, analysed.pitchHighest)
        assertEquals(8_000L, analysed.pitchVoicedMs)
        assertTrue("zählt als Änderung fürs Backup", analysed.updatedAt >= before.updatedAt)

        repository.update(id, analysed.params().copy(notes = "neu"), AttachmentChange.Keep)
        assertEquals(57, db.recordingDao().get(id)!!.pitchMostFrequent)
    }

    @Test
    fun savingAnEditNeverOverwritesAPitchResultSavedInBetween() = runBlocking {
        val id = repository.create(files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) }, null, 1L, 1L, RecordingParams())
        val snapshot = db.recordingDao().get(id)!! // vor der Analyse gelesen
        repository.savePitch(id, PitchResult(57, 52, 62, 8_000)) // Analyse wird fertig
        db.recordingDao().updateEdit(
            RecordingEdit(
                id = id,
                photoFile = snapshot.photoFile,
                documentFile = snapshot.documentFile,
                documentName = snapshot.documentName,
                title = "bearbeitet",
                exercise = snapshot.exercise,
                warmedUp = snapshot.warmedUp,
                warmUpMinutes = snapshot.warmUpMinutes,
                voiceCondition = snapshot.voiceCondition,
                selfRating = snapshot.selfRating,
                notes = snapshot.notes,
                updatedAt = 2L,
            ),
        )
        val after = db.recordingDao().get(id)!!
        assertEquals("bearbeitet", after.title)
        assertEquals(57, after.pitchMostFrequent)
        assertEquals(8_000L, after.pitchVoicedMs)
    }

    @Test
    fun missingPhotoDraftDoesNotCostTheRecording() = runBlocking {
        val audio = files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val vanishedPhoto = files.newDraftFile("jpg")

        val id = repository.create(audio, TextAttachment.Photo(vanishedPhoto), 1L, 1L, RecordingParams(title = "ohne Foto"))

        val saved = db.recordingDao().get(id)!!
        assertEquals("ohne Foto", saved.title)
        assertNull(saved.photoFile)
        assertTrue(files.audio(saved.audioFile).isFile)
    }

    @Test
    fun replacingThePhotoDeletesTheOldOne() = runBlocking {
        val id = repository.create(
            files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) },
            TextAttachment.Photo(files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(2)) }),
            1L,
            1L,
            RecordingParams(),
        )
        val before = db.recordingDao().get(id)!!
        val newPhoto = files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(3)) }

        repository.update(id, before.params().copy(notes = "neu"), AttachmentChange.Replace(TextAttachment.Photo(newPhoto)))

        val after = db.recordingDao().get(id)!!
        assertEquals("neu", after.notes)
        assertTrue(files.photo(after.photoFile!!).readBytes().contentEquals(byteArrayOf(3)))
        assertTrue("altes Foto gelöscht", !files.photo(before.photoFile!!).exists())
    }
}
