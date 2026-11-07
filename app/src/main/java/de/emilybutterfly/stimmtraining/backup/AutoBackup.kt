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
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private val library: LibraryRepository,
    private val settings: BackupSettings,
) {
    /** Liefert false, wenn die Sicherung fehlgeschlagen ist und wiederholt werden sollte. */
    suspend fun run(force: Boolean): Boolean {
        val folder = settings.current().folderUri
        if (folder == null) {
            settings.recordFailure(System.currentTimeMillis(), "Kein Backup-Ordner gewählt.")
            return true
        }
        val fingerprint = dao.fingerprint() + "|" + library.fingerprint()
        if (!force && fingerprint == settings.lastFingerprint) return true // nichts Neues
        if (dao.count() == 0 && library.count() == 0) {
            // Z. B. nach einer Neuinstallation: eine leere Sicherung würde echte verdrängen.
            if (force) settings.recordFailure(System.currentTimeMillis(), "Noch keine Aufnahmen – es gibt nichts zu sichern.")
            return true
        }
        return try {
            val tree = DocumentFile.fromTreeUri(context, folder)
            if (tree == null || !tree.isDirectory || !tree.canWrite()) {
                throw IOException("Kein Zugriff auf den Backup-Ordner. Bitte den Ordner neu wählen.")
            }
            val document = tree.createFile("application/zip", fileName(Date(), settings.installId))
                ?: throw IOException("Die Backup-Datei konnte nicht angelegt werden.")
            val count = try {
                BackupManager.openForWriting(context.contentResolver, document.uri).use { manager.export(it) }.recordings
            } catch (e: Exception) {
                document.delete()
                throw e
            }
            prune(tree, keep = document.name)
            settings.recordSuccess(System.currentTimeMillis(), count, fingerprint)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settings.recordFailure(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun prune(tree: DocumentFile, keep: String?) {
        val files = tree.listFiles().filter { it.isFile }
        val obsolete = obsoleteBackups(files.mapNotNull { it.name }, settings.installId, keep).toSet()
        files.filter { it.name in obsolete }.forEach { it.delete() }
    }

    companion object {
        const val FILE_PREFIX = "Stimmtraining-Auto-Backup-"
        const val KEEP = 5
        val INSTALL_ID = Regex("[0-9a-f]{8}")

        /**
         * Nur genau so benannte Dateien stammen vom automatischen Backup: Zeit in UTC (sortiert auch
         * über die Zeitumstellung hinweg richtig) und Kennung der Installation. Ältere Versionen
         * schrieben Ortszeit ohne Kennung.
         */
        private val AUTO_BACKUP_NAME =
            Regex("""Stimmtraining-Auto-Backup-(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})(?:Z-([0-9a-f]{8}))?\.zip""")

        fun fileName(at: Date, installId: String): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(at)
            return "$FILE_PREFIX${stamp}Z-$installId.zip"
        }

        /**
         * Die automatischen Sicherungen dieser Installation (und solche ohne Kennung aus älteren
         * Versionen), die über die neuesten [KEEP] hinausgehen. Nie gelöscht werden: [keep] (die
         * gerade angelegte – auch wenn die Uhr des Geräts falsch geht), Sicherungen anderer Geräte
         * im selben Ordner und andere Dateien (z. B. manuelle Sicherungen).
         */
        fun obsoleteBackups(names: List<String>, installId: String, keep: String? = null): List<String> =
            names.mapNotNull { name -> AUTO_BACKUP_NAME.matchEntire(name)?.let { name to it } }
                .filter { (_, match) -> match.groups[2]?.value.let { it == null || it == installId } }
                .sortedByDescending { (name, match) -> if (name == keep) "~" else match.groupValues[1] }
                .drop(KEEP)
                .map { it.first }
                .filter { it != keep }
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
