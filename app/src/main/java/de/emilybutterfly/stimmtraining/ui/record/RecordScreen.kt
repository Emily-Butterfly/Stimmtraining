package de.emilybutterfly.stimmtraining.ui.record

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.ui.components.AttachmentReader
import de.emilybutterfly.stimmtraining.ui.components.AudioPlayerBar
import de.emilybutterfly.stimmtraining.ui.components.ParameterForm
import de.emilybutterfly.stimmtraining.ui.components.ReaderSizeButtons
import de.emilybutterfly.stimmtraining.ui.components.TextAttachmentSection
import de.emilybutterfly.stimmtraining.ui.components.formatBytes
import de.emilybutterfly.stimmtraining.ui.components.formatDateTime
import de.emilybutterfly.stimmtraining.ui.components.formatDuration
import de.emilybutterfly.stimmtraining.ui.components.rememberReaderSettings
import de.emilybutterfly.stimmtraining.ui.techniques.ExerciseDetails

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val guideReady by viewModel.guideReady.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmRetake by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var readingMode by rememberSaveable { mutableStateOf(false) }
    val reading = readingMode && state.attachment != null
    val reader = rememberReaderSettings()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else permissionDenied = true
    }
    val startRecording = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it, duration = if (it.length > 80) SnackbarDuration.Long else SnackbarDuration.Short)
            viewModel.messageShown()
        }
    }

    // Bildschirm anlassen, solange aufgenommen wird – man liest ja gerade.
    val view = LocalView.current
    DisposableEffect(state.isRecording) {
        view.keepScreenOn = state.isRecording
        onDispose { view.keepScreenOn = false }
    }
    // Beim Drehen des Bildschirms kommt ebenfalls ON_STOP – dann läuft die Aufnahme weiter.
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) viewModel.onAppBackgrounded()
    }

    val requestClose = {
        when {
            state.saving -> Unit
            state.hasDraft -> confirmLeave = true
            else -> onClose()
        }
    }
    BackHandler(enabled = state.hasDraft || state.saving) { requestClose() }
    // Später registriert, hat also Vorrang: „Zurück“ beendet zuerst den Lesemodus.
    BackHandler(enabled = reading) { readingMode = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reading) "Lesen" else "Neue Aufnahme") },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    val attachment = state.attachment
                    if (reading && attachment is TextAttachment.Document) ReaderSizeButtons(reader, attachment.kind)
                    if (attachment != null) {
                        IconButton(onClick = { readingMode = !reading }) {
                            if (reading) {
                                Icon(Icons.Filled.CloseFullscreen, contentDescription = "Lesemodus beenden")
                            } else {
                                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = "Lesemodus")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            RecorderPanel(
                state = state,
                onStart = startRecording,
                onPauseResume = viewModel::pauseOrResume,
                onStop = viewModel::stopRecording,
                onRetake = { confirmRetake = true },
                onSave = { viewModel.save(onSaved) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!guideReady) return@Scaffold
        val attachment = state.attachment
        if (reading && attachment != null) {
            // Der Text füllt den Platz zwischen Titelleiste und Aufnahme-Knöpfen.
            AttachmentReader(attachment, reader, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            guide?.let { GuideCard(it) }
            TextAttachmentSection(
                attachment = attachment,
                busy = state.attachmentBusy,
                reader = reader,
                cameraTarget = viewModel::cameraTarget,
                onCameraResult = viewModel::onCameraPhoto,
                onPhotoPicked = viewModel::onPhotoPicked,
                onDocumentPicked = viewModel::onDocumentPicked,
                onRemove = viewModel::removeAttachment,
                onExpand = { readingMode = true },
                onOpenExternally = { document -> viewModel.openExternally(document) { context.startActivity(it) } },
                // Kamera, Galerie oder Dateiauswahl würden die App in den Hintergrund schicken und die Aufnahme beenden.
                enabled = !state.isRecording,
            )
            ParameterForm(params = state.params, onChange = viewModel::updateParams)
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Verwerfen?") },
            text = { Text("Die Aufnahme und deine Angaben werden nicht gespeichert.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    viewModel.discardDraft()
                    onClose()
                }) { Text("Verwerfen") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Weiter bearbeiten") } },
        )
    }

    if (confirmRetake) {
        AlertDialog(
            onDismissRequest = { confirmRetake = false },
            title = { Text("Neu aufnehmen?") },
            text = { Text("Die bisherige Aufnahme wird gelöscht. Text und Angaben bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetake = false
                    viewModel.discardRecording()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmRetake = false }) { Text("Abbrechen") } },
        )
    }

    state.storageWarning?.let { warning ->
        StorageWarningDialog(
            warning = warning,
            onRecordAnyway = { viewModel.startRecording(ignoreLowStorage = true) },
            onManageStorage = {
                viewModel.dismissStorageWarning()
                openStorageSettings(context)
            },
            onDismiss = viewModel::dismissStorageWarning,
        )
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            title = { Text("Mikrofon-Zugriff nötig") },
            text = {
                Text("Ohne Zugriff auf das Mikrofon kann die App nicht aufnehmen. Du kannst ihn in den App-Einstellungen erlauben.")
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Einstellungen öffnen") }
            },
            dismissButton = { TextButton(onClick = { permissionDenied = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun StorageWarningDialog(
    warning: StorageWarning,
    onRecordAnyway: () -> Unit,
    onManageStorage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val free = formatBytes(warning.freeBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SdStorage, contentDescription = null) },
        title = { Text(if (warning.full) "Speicher voll" else "Wenig Speicherplatz") },
        text = {
            if (warning.full) {
                Text(
                    "Auf dem Handy sind nur noch $free frei – zu wenig für eine Aufnahme. " +
                        "Gib Speicher frei, z. B. indem du Videos auslagerst oder Apps entfernst, die du nicht brauchst.",
                )
            } else {
                val minutes = if (warning.minutesLeft == 1L) "1 Minute" else "${warning.minutesLeft} Minuten"
                Text(
                    "Auf dem Handy sind nur noch $free frei. Das reicht für etwa $minutes Aufnahme. " +
                        "Wird der Platz knapp, beendet die App die Aufnahme rechtzeitig, sodass sie erhalten bleibt.",
                )
            }
        },
        confirmButton = {
            if (warning.full) {
                TextButton(onClick = onManageStorage) { Text("Speicher verwalten") }
            } else {
                TextButton(onClick = onRecordAnyway) { Text("Trotzdem aufnehmen") }
            }
        },
        dismissButton = {
            Row {
                if (!warning.full) TextButton(onClick = onManageStorage) { Text("Speicher verwalten") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

private fun openStorageSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun RecorderPanel(
    state: RecordUiState,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.recordedAt?.let {
                Text(
                    formatDateTime(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state.phase) {
                RecorderPhase.Idle -> {
                    FilledIconButton(onClick = onStart, modifier = Modifier.size(72.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Aufnahme starten", modifier = Modifier.size(36.dp))
                    }
                    Text("Tippen, um die Aufnahme zu starten", style = MaterialTheme.typography.bodySmall)
                }

                RecorderPhase.Recording, RecorderPhase.Paused -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecordingIndicator(active = state.phase == RecorderPhase.Recording)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatDuration(state.elapsedMs),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                        if (state.phase == RecorderPhase.Paused) {
                            Text(
                                "  pausiert",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LevelMeter(state.level)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = onPauseResume, modifier = Modifier.size(56.dp)) {
                            if (state.phase == RecorderPhase.Recording) {
                                Icon(Icons.Filled.Pause, contentDescription = "Pausieren")
                            } else {
                                Icon(Icons.Filled.Mic, contentDescription = "Fortsetzen")
                            }
                        }
                        FilledIconButton(
                            onClick = onStop,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "Aufnahme beenden", modifier = Modifier.size(36.dp))
                        }
                    }
                }

                RecorderPhase.Done -> {
                    state.audio?.let { AudioPlayerBar(it, Modifier.fillMaxWidth()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onRetake, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Neu aufnehmen")
                        }
                        Button(
                            onClick = onSave,
                            enabled = !state.saving && !state.attachmentBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Speichern")
                        }
                    }
                }
            }
        }
    }
}

/** Die Anleitung der gewählten Übung, damit sie beim Aufnehmen sichtbar ist. */
@Composable
private fun GuideCard(guide: ExerciseGuide) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Übung", style = MaterialTheme.typography.labelMedium)
                    Text(guide.label, style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Anleitung ausblenden" else "Anleitung anzeigen",
                    )
                }
            }
            if (expanded) {
                Text(guide.exercise.goal, style = MaterialTheme.typography.bodyMedium)
                ExerciseDetails(guide.exercise)
            }
        }
    }
}

@Composable
private fun RecordingIndicator(active: Boolean) {
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "rec")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "rec-alpha",
        ).value
    } else {
        0.4f
    }
    Box(
        Modifier
            .size(14.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
    )
}

@Composable
private fun LevelMeter(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
