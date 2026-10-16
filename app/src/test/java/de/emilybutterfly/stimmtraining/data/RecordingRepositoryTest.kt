package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
            repository.create(audio, photo, 1L, 1L, RecordingParams())
            fail("Speichern hätte fehlschlagen müssen")
        } catch (_: Exception) {
        }

        assertTrue("Audio-Entwurf ist wieder da", audio.isFile)
        assertTrue("Foto-Entwurf ist wieder da", photo.isFile)
        assertEquals(0, files.audioDir.listFiles()?.size ?: 0)
        assertEquals(0, files.photoDir.listFiles()?.size ?: 0)
    }

    @Test
    fun missingPhotoDraftDoesNotCostTheRecording() = runBlocking {
        val audio = files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val vanishedPhoto = files.newDraftFile("jpg")

        val id = repository.create(audio, vanishedPhoto, 1L, 1L, RecordingParams(title = "ohne Foto"))

        val saved = db.recordingDao().get(id)!!
        assertEquals("ohne Foto", saved.title)
        assertNull(saved.photoFile)
        assertTrue(files.audio(saved.audioFile).isFile)
    }

    @Test
    fun replacingThePhotoDeletesTheOldOne() = runBlocking {
        val id = repository.create(
            files.newDraftFile("m4a").apply { writeBytes(byteArrayOf(1)) },
            files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(2)) },
            1L,
            1L,
            RecordingParams(),
        )
        val before = db.recordingDao().get(id)!!
        val newPhoto = files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(3)) }

        repository.update(id, before.params().copy(notes = "neu"), PhotoChange.Replace(newPhoto))

        val after = db.recordingDao().get(id)!!
        assertEquals("neu", after.notes)
        assertTrue(files.photo(after.photoFile!!).readBytes().contentEquals(byteArrayOf(3)))
        assertTrue("altes Foto gelöscht", !files.photo(before.photoFile!!).exists())
    }
}
