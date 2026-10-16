package de.emilybutterfly.stimmtraining.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.backup.AutoBackup
import de.emilybutterfly.stimmtraining.backup.AutoBackupInterval
import de.emilybutterfly.stimmtraining.ui.components.formatBytes
import de.emilybutterfly.stimmtraining.ui.components.formatDateTime
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onClose: () -> Unit,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val auto by viewModel.auto.collectAsStateWithLifecycle()
    val autoRunning by viewModel.autoRunning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.chooseFolder(uri)
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "${ui.recordingCount} Aufnahmen · ${formatBytes(ui.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ui.busyLabel?.let { label ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sicherung als Datei", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Speichert alle Aufnahmen mit Fotos, Datum, Uhrzeit und Angaben in einer ZIP-Datei. " +
                            "Als Ziel kannst du z. B. Google Drive, den Download-Ordner oder einen USB-Stick wählen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { exportLauncher.launch("Stimmtraining-Backup-${LocalDate.now()}.zip") },
                        enabled = ui.busyLabel == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Backup erstellen")
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        },
                        enabled = ui.busyLabel == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Restore, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Backup wiederherstellen")
                    }
                    Text(
                        "Beim Wiederherstellen werden nur fehlende Aufnahmen ergänzt – vorhandene bleiben unverändert, nichts wird gelöscht.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Automatisches Backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Legt regelmäßig eine Sicherung in einem Ordner deiner Wahl ab, wenn sich seit der letzten etwas geändert hat. " +
                            "Die letzten ${AutoBackup.KEEP} Sicherungen bleiben erhalten.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    val folderName = remember(auto.folderUri) { auto.folderUri?.let(viewModel::folderName) }
                    Row {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            folderName?.let { "Ordner: $it" } ?: "Kein Ordner gewählt",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    FilledTonalButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (auto.folderUri == null) "Ordner wählen" else "Anderen Ordner wählen")
                    }

                    Text("Häufigkeit", style = MaterialTheme.typography.titleSmall)
                    val intervals = AutoBackupInterval.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        intervals.forEachIndexed { index, interval ->
                            SegmentedButton(
                                selected = auto.interval == interval,
                                onClick = { viewModel.setInterval(interval) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = intervals.size),
                                enabled = auto.folderUri != null || interval == AutoBackupInterval.OFF,
                            ) { Text(interval.label) }
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::backupNow,
                        enabled = auto.folderUri != null && !autoRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (autoRunning) "Sicherung läuft …" else "Jetzt in den Ordner sichern")
                    }

                    auto.lastSuccessAt?.let {
                        Text(
                            "Letzte Sicherung: ${formatDateTime(it)} (${auto.lastCount} Aufnahmen)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val errorAt = auto.lastErrorAt
                    if (auto.lastError != null && errorAt != null) {
                        Text(
                            "Fehler am ${formatDateTime(errorAt)}: ${auto.lastError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Tipp: Am sichersten ist ein Ordner, den eine Sync-App (z. B. Nextcloud, Syncthing oder Dropsync) in die Cloud " +
                            "oder auf deinen Computer spiegelt. Google Drive lässt sich hier nicht als Ordner wählen – " +
                            "dafür oben „Backup erstellen“ nutzen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
