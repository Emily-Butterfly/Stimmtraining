package de.emilybutterfly.stimmtraining.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
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
        val obsolete = AutoBackup.obsoleteBackups(auto.shuffled() + others, installId = "0badc0de")
        assertEquals(auto.take(2).toSet(), obsolete.toSet())
    }

    @Test
    fun rotationLeavesOtherDevicesAndTheNewestFileAlone() {
        val mine = (1..6).map { "Stimmtraining-Auto-Backup-2026-10-0${it}_12-00-00Z-aaaaaaaa.zip" }
        val otherDevice = (1..6).map { "Stimmtraining-Auto-Backup-2026-10-0${it}_13-00-00Z-bbbbbbbb.zip" }
        // Uhr des Geräts falsch gestellt: Die gerade angelegte Sicherung sortiert ganz unten.
        val justCreated = "Stimmtraining-Auto-Backup-2001-01-01_00-00-00Z-aaaaaaaa.zip"
        val obsolete = AutoBackup.obsoleteBackups(mine + otherDevice + justCreated, installId = "aaaaaaaa", keep = justCreated)
        assertEquals(mine.take(2).toSet(), obsolete.toSet())
    }

    @Test
    fun fileNamesUseUtcAndTheInstallId() {
        val name = AutoBackup.fileName(java.util.Date(0), "1234abcd")
        assertEquals("Stimmtraining-Auto-Backup-1970-01-01_00-00-00Z-1234abcd.zip", name)
        assertEquals(emptyList<String>(), AutoBackup.obsoleteBackups(listOf(name), installId = "1234abcd"))
        val id = BackupSettings(context).installId
        assertTrue(AutoBackup.INSTALL_ID.matches(id))
        assertEquals(id, BackupSettings(context).installId)
    }

    @Test
    fun emptyDatabaseIsNeverBackedUp() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val settings = BackupSettings(context)
        settings.setFolder(Uri.parse("content://nirgendwo/tree/ordner"))
        val autoBackup = AutoBackup(context, BackupManager(context, db.recordingDao(), LibraryRepository(db.libraryDao(), FileStore(context)), FileStore(context)), db.recordingDao(), LibraryRepository(db.libraryDao(), FileStore(context)), settings)

        assertTrue(autoBackup.run(force = true))

        // Abgebrochen, bevor der (hier ungültige) Ordner überhaupt angefasst wurde.
        assertEquals("Noch keine Aufnahmen – es gibt nichts zu sichern.", settings.current().lastError)
        db.close()
    }
}
