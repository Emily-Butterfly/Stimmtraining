package de.emilybutterfly.stimmtraining.ui.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.backup.AutoBackupInterval
import de.emilybutterfly.stimmtraining.backup.AutoBackupScheduler
import de.emilybutterfly.stimmtraining.backup.AutoBackupState
import de.emilybutterfly.stimmtraining.backup.BackupException
import de.emilybutterfly.stimmtraining.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val busyLabel: String? = null,
    val message: String? = null,
    val recordingCount: Int = 0,
    val totalBytes: Long = 0,
)

class BackupViewModel(
    private val app: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val settings = container.backupSettings

    private val _ui = MutableStateFlow(BackupUiState())
    val ui: StateFlow<BackupUiState> = _ui.asStateFlow()

    val auto: StateFlow<AutoBackupState> = settings.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.current())

    val autoRunning: StateFlow<Boolean> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(AutoBackupScheduler.ONE_TIME_WORK)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            container.repository.observeAll().collect { recordings ->
                val bytes = withContext(Dispatchers.IO) { container.files.totalBytes() }
                _ui.update { it.copy(recordingCount = recordings.size, totalBytes = bytes) }
            }
        }
    }

    fun export(uri: Uri) = runOperation("Sicherung wird erstellt …") {
        val count = container.backupManager.exportTo(uri)
        "Sicherung gespeichert ($count Aufnahmen)."
    }

    fun import(uri: Uri) = runOperation("Sicherung wird eingelesen …") {
        val result = container.backupManager.importFrom(uri)
        buildString {
            append(
                when (result.imported) {
                    0 -> "Keine neuen Aufnahmen gefunden"
                    1 -> "1 Aufnahme wiederhergestellt"
                    else -> "${result.imported} Aufnahmen wiederhergestellt"
                },
            )
            if (result.skipped > 0) append(", ${result.skipped} waren schon vorhanden")
            if (result.incomplete > 0) append(", ${result.incomplete} unvollständig (Audiodatei fehlt)")
            append(".")
        }
    }

    private fun runOperation(label: String, block: suspend () -> String) {
        if (_ui.value.busyLabel != null) return
        _ui.update { it.copy(busyLabel = label) }
        viewModelScope.launch {
            val message = try {
                // Nicht abbrechen, wenn die Ansicht verlassen wird – sonst bliebe eine halbe Datei zurück.
                withContext(NonCancellable) { block() }
            } catch (e: BackupException) {
                e.message ?: "Fehler beim Backup."
            } catch (e: Exception) {
                "Fehler: ${e.message ?: e.javaClass.simpleName}"
            }
            _ui.update { it.copy(busyLabel = null, message = message) }
        }
    }

    fun chooseFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = app.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            _ui.update { it.copy(message = "Auf diesen Ordner darf die App nicht dauerhaft zugreifen. Bitte einen anderen wählen.") }
            return
        }
        val previous = settings.current().folderUri
        if (previous != null && previous != uri) {
            runCatching { resolver.releasePersistableUriPermission(previous, flags) }
        }
        settings.setFolder(uri)
        if (settings.current().interval == AutoBackupInterval.OFF) setInterval(AutoBackupInterval.WEEKLY)
    }

    fun setInterval(interval: AutoBackupInterval) {
        settings.setInterval(interval)
        AutoBackupScheduler.schedule(app, interval)
    }

    fun backupNow() = AutoBackupScheduler.runNow(app)

    fun folderName(uri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(app, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast(':')
            ?: uri.toString()

    fun messageShown() = _ui.update { it.copy(message = null) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = app()
                BackupViewModel(app, app.container)
            }
        }
    }
}
