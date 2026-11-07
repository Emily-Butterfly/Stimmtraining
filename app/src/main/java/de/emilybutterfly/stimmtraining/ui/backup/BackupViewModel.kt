package de.emilybutterfly.stimmtraining.ui.backup

import de.emilybutterfly.stimmtraining.ui.components.recordingCount
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
import de.emilybutterfly.stimmtraining.backup.BackupActivity
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

    /** Name des gewählten Ordners; abgefragt im Hintergrund, denn manche Anbieter (USB, Cloud) brauchen lange. */
    val folderName: StateFlow<String?> = settings.state
        .map { state -> state.folderUri?.let { uri -> withContext(Dispatchers.IO) { folderName(uri) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Läuft schon eine Sicherung (auch aus einem früheren Besuch dieser Ansicht), ist das hier zu sehen.
        viewModelScope.launch {
            container.backupManager.activity.collect { activity ->
                _ui.update {
                    it.copy(
                        busyLabel = when (activity) {
                            BackupActivity.Export -> "Sicherung wird erstellt …"
                            BackupActivity.Import -> "Sicherung wird eingelesen …"
                            null -> null
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            container.repository.observeAll().collect { recordings ->
                val bytes = withContext(Dispatchers.IO) { container.files.totalBytes() }
                _ui.update { it.copy(recordingCount = recordings.size, totalBytes = bytes) }
            }
        }
    }

    fun export(uri: Uri) = runOperation {
        val result = container.backupManager.exportTo(uri)
        val texts = if (result.texts == 1) "1 Text" else "${result.texts} Texte"
        "Sicherung gespeichert (${recordingCount(result.recordings)}, $texts der Bibliothek)."
    }

    fun import(uri: Uri) = runOperation {
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
            if (result.libraryImported > 0) {
                append(if (result.libraryImported == 1) ", 1 Text für die Bibliothek" else ", ${result.libraryImported} Texte für die Bibliothek")
            }
            append(".")
        }
    }

    /** Den Fortschritt zeigt [BackupManager.activity]; hier kommt nur die Meldung am Ende. */
    private fun runOperation(block: suspend () -> String) {
        viewModelScope.launch {
            val message = try {
                // Nicht abbrechen, wenn die Ansicht verlassen wird – sonst bliebe eine halbe Datei zurück.
                withContext(NonCancellable) { block() }
            } catch (e: BackupException) {
                e.message ?: "Fehler beim Backup."
            } catch (e: Exception) {
                "Fehler: ${e.message ?: e.javaClass.simpleName}"
            } catch (_: OutOfMemoryError) {
                "Die Sicherung ist zu groß für den Arbeitsspeicher."
            }
            _ui.update { it.copy(message = message) }
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
        // Auch bei schon gewählter Häufigkeit neu planen, z. B. nach dem Umzug auf ein neues Gerät.
        val interval = settings.current().interval.takeIf { it != AutoBackupInterval.OFF } ?: AutoBackupInterval.WEEKLY
        setInterval(interval)
    }

    fun setInterval(interval: AutoBackupInterval) {
        settings.setInterval(interval)
        AutoBackupScheduler.schedule(app, interval)
    }

    fun backupNow() = AutoBackupScheduler.runNow(app)

    private fun folderName(uri: Uri): String =
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
