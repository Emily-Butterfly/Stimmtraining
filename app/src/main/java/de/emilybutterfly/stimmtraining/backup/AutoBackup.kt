package de.emilybutterfly.stimmtraining.backup

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.RecordingDao
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Legt Sicherungen in einem selbst gewählten Ordner ab (z. B. lokal, auf einer SD-Karte
 * oder in einem Cloud-Ordner, den eine Sync-App bereitstellt) und behält die letzten [KEEP].
 */
class AutoBackup(
    private val context: Context,
    private val manager: BackupManager,
    private val dao: RecordingDao,
    private val settings: BackupSettings,
) {
    /** Liefert false, wenn die Sicherung fehlgeschlagen ist und wiederholt werden sollte. */
    suspend fun run(force: Boolean): Boolean {
        val folder = settings.current().folderUri
        if (folder == null) {
            settings.recordFailure(System.currentTimeMillis(), "Kein Backup-Ordner gewählt.")
            return true
        }
        val fingerprint = dao.fingerprint()
        if (!force && fingerprint == settings.lastFingerprint) return true // nichts Neues
        return try {
            val tree = DocumentFile.fromTreeUri(context, folder)
            if (tree == null || !tree.isDirectory || !tree.canWrite()) {
                throw IOException("Kein Zugriff auf den Backup-Ordner. Bitte den Ordner neu wählen.")
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
            val document = tree.createFile("application/zip", "$FILE_PREFIX$stamp.zip")
                ?: throw IOException("Die Backup-Datei konnte nicht angelegt werden.")
            val count = try {
                context.contentResolver.openOutputStream(document.uri, "w")?.use { manager.export(it) }
                    ?: throw IOException("Die Backup-Datei konnte nicht geschrieben werden.")
            } catch (e: Exception) {
                document.delete()
                throw e
            }
            prune(tree)
            settings.recordSuccess(System.currentTimeMillis(), count, fingerprint)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settings.recordFailure(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun prune(tree: DocumentFile) {
        tree.listFiles()
            .filter { it.isFile && it.name.orEmpty().let { n -> n.startsWith(FILE_PREFIX) && n.endsWith(".zip") } }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { it.delete() }
    }

    companion object {
        const val FILE_PREFIX = "Stimmtraining-Backup-"
        const val KEEP = 5
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StimmtrainingApp).container
        val ok = container.autoBackup.run(force = inputData.getBoolean(KEY_FORCE, false))
        return when {
            ok -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}

object AutoBackupScheduler {
    private const val PERIODIC_WORK = "auto-backup"
    const val ONE_TIME_WORK = "auto-backup-now"

    fun schedule(context: Context, interval: AutoBackupInterval) {
        val workManager = WorkManager.getInstance(context)
        if (interval == AutoBackupInterval.OFF) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval.days, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInputData(workDataOf(AutoBackupWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
