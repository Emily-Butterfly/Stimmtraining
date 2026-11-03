package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.data.library.LibraryText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Ein Update der App behält alle Aufnahmen, die mit der vorigen Datenbank-Version gespeichert wurden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Test
    fun version1RecordingsSurviveTheUpdate() = runBlocking {
        context.deleteDatabase(name)
        createVersion1Database()

        // Room prüft nach der Migration, ob die Tabelle genau dem neuen Schema entspricht.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val recording = db.recordingDao().getAll().single()
            assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", recording.uuid)
            assertEquals(1_791_000_000_000, recording.recordedAt)
            assertEquals("a_1.jpg", recording.photoFile)
            assertEquals("Nordwind", recording.title)
            assertEquals(10, recording.warmUpMinutes)
            assertEquals(5, recording.selfRating)
            assertNull(recording.documentFile)
            assertNull(recording.documentName)
            assertNull("noch nicht analysiert", recording.pitchVoicedMs)
            assertNull(recording.pitchMostFrequent)
            assertEquals(listOf("a_1.jpg"), recording.photoPages)
            assertNull(recording.sectionStartY)

            db.recordingDao().update(recording.copy(photoFile = null, documentFile = "b.pdf", documentName = "Nordwind.pdf"))
            assertEquals("Nordwind.pdf", db.recordingDao().get(recording.id)?.documentName)
            db.recordingDao().updatePitch(recording.id, 57, 52, 62, 9_000, updatedAt = 2L)
            val analysed = db.recordingDao().get(recording.id)
            assertEquals(57, analysed?.pitchMostFrequent)
            assertEquals(9_000L, analysed?.pitchVoicedMs)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun version2RecordingsSurviveAndTheLibraryWorks() = runBlocking {
        context.deleteDatabase(name)
        createDatabase(SCHEMA_2)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val recording = db.recordingDao().getAll().single()
            assertEquals("Nordwind", recording.title)
            assertEquals(listOf("a_1.jpg"), recording.photoPages)
            db.recordingDao().update(recording.copy(photoFile = "a_1.jpg\na_2.jpg", sectionStartY = 0.25f, sectionEndY = 0.5f))
            val twoPages = db.recordingDao().get(recording.id)!!
            assertEquals(listOf("a_1.jpg", "a_2.jpg"), twoPages.photoPages)
            assertEquals(0.25f, twoPages.sectionStartY)

            val library = db.libraryDao()
            val id = library.insertWithSearch(
                LibraryText(uuid = "c9bf9e57-1685-4c89-bafb-ff5af830be8a", title = "Der Prozess", createdAt = 1, updatedAt = 1),
                content = "der prozess\njemand musste josef k. verleumdet haben",
            )
            assertEquals(listOf(id), library.observeMatches("josef").first())
            assertEquals(emptyList<Long>(), library.observeMatches("nordwind").first())
            library.delete(id)
            assertEquals(0, library.count())
            assertNull(library.searchContent(id))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersion1Database() = createDatabase(SCHEMA_1)

    /** Legt die Datenbank so an, wie eine frühere Version sie hinterlassen hat – nach dem exportierten Schema. */
    private fun createDatabase(schemaFile: String) {
        val schema = JSONObject(File(schemaFile).readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL(
                "INSERT INTO recordings (uuid, recordedAt, durationMs, audioFile, photoFile, title, exercise, warmedUp, " +
                    "warmUpMinutes, voiceCondition, selfRating, notes, updatedAt) VALUES " +
                    "('0f8fad5b-d9cb-469f-a165-70867728950e', 1791000000000, 61000, 'a.m4a', 'a_1.jpg', 'Nordwind', " +
                    "'Text lesen', 1, 10, 4, 5, 'gut', 1791000100000)",
            )
            sqlite.version = schema.getInt("version")
        } finally {
            sqlite.close()
        }
    }

    private companion object {
        /** Gradle führt die Tests im Modulordner aus. */
        const val SCHEMA_1 = "schemas/de.emilybutterfly.stimmtraining.data.AppDatabase/1.json"
        const val SCHEMA_2 = "schemas/de.emilybutterfly.stimmtraining.data.AppDatabase/2.json"
    }
}
