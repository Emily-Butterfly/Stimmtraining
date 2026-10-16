package de.emilybutterfly.stimmtraining

import android.app.Application
import android.content.Context
import de.emilybutterfly.stimmtraining.backup.AutoBackup
import de.emilybutterfly.stimmtraining.backup.BackupManager
import de.emilybutterfly.stimmtraining.backup.BackupSettings
import de.emilybutterfly.stimmtraining.data.AppDatabase
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class StimmtrainingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Einfache manuelle Dependency Injection für die ganze App. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = AppDatabase.build(context)
    val files = FileStore(context)
    val repository = RecordingRepository(database.recordingDao(), files)
    val backupManager = BackupManager(context, database.recordingDao(), files)
    val backupSettings = BackupSettings(context)
    val autoBackup = AutoBackup(context, backupManager, database.recordingDao(), backupSettings)
}
