package de.emilybutterfly.stimmtraining.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.data.AppDatabase
import de.emilybutterfly.stimmtraining.data.FileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rotationOnlyDeletesOlderAutomaticBackups() {
        val auto = (1..7).map { "Stimmtraining-Auto-Backup-2026-10-0${it}_12-00-00.zip" }
        val others = listOf(
            "Stimmtraining-Backup-2026-10-01.zip", // manuelles Backup
            "Stimmtraining-Auto-Backup-2026-10-01_12-00-00 (1).zip", // Kopie des Anbieters
            "Notizen.txt",
        )
        val obsolete = AutoBackup.obsoleteBackups(auto.shuffled() + others)
        assertEquals(auto.take(2).toSet(), obsolete.toSet())
    }

    @Test
    fun emptyDatabaseIsNeverBackedUp() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val settings = BackupSettings(context)
        settings.setFolder(Uri.parse("content://nirgendwo/tree/ordner"))
        val autoBackup = AutoBackup(context, BackupManager(context, db.recordingDao(), FileStore(context)), db.recordingDao(), settings)

        assertTrue(autoBackup.run(force = true))

        // Abgebrochen, bevor der (hier ungültige) Ordner überhaupt angefasst wurde.
        assertEquals("Noch keine Aufnahmen – es gibt nichts zu sichern.", settings.current().lastError)
        db.close()
    }
}
