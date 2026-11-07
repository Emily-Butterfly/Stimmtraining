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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.ui.components.AttachmentPreview
import de.emilybutterfly.stimmtraining.ui.components.DocumentContent
import de.emilybutterfly.stimmtraining.ui.components.ReaderSettings
import de.emilybutterfly.stimmtraining.ui.components.ReadingMarks
import de.emilybutterfly.stimmtraining.ui.components.AttachmentReader
import de.emilybutterfly.stimmtraining.ui.components.AudioPlayerBar
import de.emilybutterfly.stimmtraining.ui.components.ParameterForm
import de.emilybutterfly.stimmtraining.ui.components.PitchCard
import de.emilybutterfly.stimmtraining.ui.components.ReaderSizeButtons
import de.emilybutterfly.stimmtraining.ui.components.TextAttachmentSection
import de.emilybutterfly.stimmtraining.ui.components.formatBytes
import de.emilybutterfly.stimmtraining.ui.components.formatDateTime
import de.emilybutterfly.stimmtraining.ui.components.formatDuration
import de.emilybutterfly.stimmtraining.ui.components.rememberDocumentContent
import de.emilybutterfly.stimmtraining.ui.components.rememberReaderSettings
import de.emilybutterfly.stimmtraining.ui.techniques.ExerciseDetails

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    onPickFromLibrary: () -> Unit = {},
    pickedLibraryText: Long? = null,
    onPickedLibraryTextHandled: () -> Unit = {},
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
    val shownText = state.shownText
    val reading = readingMode && shownText != null
    val reader = rememberReaderSettings()
    // Inline-Karte und Lesemodus sind nie gleichzeitig zu sehen und teilen sich die Leseposition;
    // das Formular behält seine Scrollposition, während der Lesemodus offen ist.
    val documentListState = rememberSaveable(shownText?.files?.firstOrNull(), state.library?.start, saver = LazyListState.Saver) { LazyListState() }
    val formScroll = rememberScrollState()
    // Das Formular behält beim Wechsel in den Lesemodus seinen ganzen Zustand (z. B. eingeklappte Anleitung).
    val formState = rememberSaveableStateHolder()
    // Ein Bibliothekstext zeigt nur den Rest; angetippt wird, bis wohin gelesen wurde.
    val library = state.library
    val marks = library?.let {
        ReadingMarks(
            start = it.start,
            end = it.end,
            onMark = viewModel::markLibraryEnd.takeIf { !state.saving },
            // Der letzte Satz (bzw. die letzten Zeilen) angetippt: „Bis zum Ende“.
            onMarkEnd = { viewModel.markLibraryEnd(null) },
        )
    }
    // Einmal geladen, für Karte und Lesemodus: kein erneutes Laden und kein Springen beim Umschalten.
    val attachedDocument = shownText as? TextAttachment.Document
    val documentContent = attachedDocument?.let { document -> key(document.file) { rememberDocumentContent(document).value } }

    // Läuft die Frage nach dem Mikrofon schon, löst ein zweiter Tipp keine zweite aus: Android
    // würde sie sofort ablehnen und die echte Antwort zurückhalten.
    var askingPermission by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askingPermission = false
        if (granted) viewModel.startRecording() else permissionDenied = true
    }
    val startRecording = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        when {
            granted -> viewModel.startRecording()
            !askingPermission -> {
                askingPermission = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Gespeichert: zur Aufnahme wechseln – mit dem Rückruf dieser Ansicht, nicht einem von vor dem Drehen.
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.savedId) {
        state.savedId?.let { currentOnSaved(it) }
    }

    // Nach dem Stoppen soll die Frage „Wie weit hast du gelesen?“ zu sehen sein, nicht irgendwo weiter unten.
    LaunchedEffect(state.phase == RecorderPhase.Done, library != null) {
        if (state.phase == RecorderPhase.Done && library != null) formScroll.animateScrollTo(0)
    }

    // Aus der Bibliothek gewählt: einmal übernehmen.
    LaunchedEffect(pickedLibraryText) {
        if (pickedLibraryText != null) {
            viewModel.selectLibraryText(pickedLibraryText)
            onPickedLibraryTextHandled()
        }
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
                title = { Text(if (reading) "Lesen" else "Neue Aufnahme", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    // Im Lesemodus führt „Zurück“ wie die Zurück-Geste erst zum Formular.
                    IconButton(onClick = { if (reading) readingMode = false else requestClose() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (reading) "Lesemodus beenden" else "Zurück",
                        )
                    }
                },
                actions = {
                    val attachment = shownText
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
                onSave = viewModel::save,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!guideReady) return@Scaffold
        val attachment = state.attachment
        if (reading && shownText != null) {
            // Der Text füllt den Platz zwischen Titelleiste und Aufnahme-Knöpfen.
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (library != null) {
                    MarkHint(
                        library,
                        finished = state.phase == RecorderPhase.Done,
                        enabled = !state.saving,
                        onToEnd = { viewModel.markLibraryEnd(null) },
                    )
                }
                AttachmentReader(
                    shownText,
                    reader,
                    Modifier.weight(1f).fillMaxWidth(),
                    documentListState = documentListState,
                    documentContent = documentContent,
                    marks = marks,
                )
            }
            return@Scaffold
        }
        formState.SaveableStateProvider("form") {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(formScroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.phase == RecorderPhase.Done && library != null) {
                    ReadingQuestion(
                        reading = library,
                        enabled = !state.saving,
                        onToEnd = { viewModel.markLibraryEnd(null) },
                        onChoosePosition = { readingMode = true },
                    )
                }
                if (state.phase == RecorderPhase.Done) {
                    PitchCard(
                        result = state.pitch,
                        progress = state.pitchProgress,
                        onAnalyze = viewModel::analyzePitch,
                        enabled = !state.saving,
                    )
                }
                guide?.let { GuideCard(it) }
                if (library != null && marks != null) {
                    LibraryReadingCard(
                        reading = library,
                        busy = state.attachmentBusy,
                        enabled = !state.isRecording && !state.saving,
                        reader = reader,
                        marks = marks,
                        documentListState = documentListState,
                        documentContent = documentContent,
                        onExpand = { readingMode = true },
                        onPickOther = onPickFromLibrary,
                        onRemove = viewModel::removeLibraryText,
                    )
                } else TextAttachmentSection(
                    attachment = attachment,
                    busy = state.attachmentBusy,
                    reader = reader,
                    cameraTarget = viewModel::cameraTarget,
                    onCameraResult = viewModel::onCameraPhoto,
                    onPhotosPicked = viewModel::onPhotosPicked,
                    onRotatePage = viewModel::rotatePage,
                    onMovePage = viewModel::movePage,
                    onDeletePage = viewModel::deletePage,
                    onDocumentPicked = viewModel::onDocumentPicked,
                    onTextPasted = viewModel::onTextPasted,
                    onRemove = viewModel::removeAttachment,
                    onExpand = { readingMode = true },
                    documentListState = documentListState,
                    documentContent = documentContent,
                    onOpenExternally = { document -> viewModel.openExternally(document) { context.startActivity(it) } },
                    // Kamera, Galerie oder Dateiauswahl würden die App in den Hintergrund schicken und die Aufnahme beenden.
                    enabled = !state.isRecording && !state.saving,
                    onPickFromLibrary = onPickFromLibrary,
                    onAddToLibrary = viewModel::addAttachmentToLibrary.takeIf { attachment != null },
                )
                ParameterForm(params = state.params, onChange = viewModel::updateParams, enabled = !state.saving)
            }
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

/**
 * Hinweis über dem Text im Lesemodus: antippen, bis wohin gelesen wurde. Nach dem Stoppen
 * ([finished]) steht hier die Frage „Wie weit hast du gelesen?“ samt dem, was ohne Antippen gilt.
 */
@Composable
private fun MarkHint(reading: LibraryReading, finished: Boolean, enabled: Boolean, onToEnd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (finished) Text("Wie weit hast du gelesen?", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        reading.end != null -> "Gelesen ${describeEnd(reading.end, maxAnchor = 30)}. Tippe neu, um es zu ändern."
                        finished -> "Tippe auf die Stelle. Ohne Antippen gilt: bis zum Ende gelesen."
                        else -> "Tippe auf die Stelle, bis zu der du gelesen hast."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reading.end != null) TextButton(onClick = onToEnd, enabled = enabled) { Text("Bis zum Ende") }
        }
    }
}

/** Nach dem Stoppen: Wie weit wurde gelesen? „Bis zum Ende“ ist vorausgewählt. */
@Composable
private fun ReadingQuestion(reading: LibraryReading, enabled: Boolean, onToEnd: () -> Unit, onChoosePosition: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                "Wie weit hast du gelesen?",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ChoiceRow(
                selected = reading.end == null,
                enabled = enabled,
                title = "Bis zum Ende",
                detail = "Der Text wandert beim Speichern nach „Gelesen“.",
                onClick = onToEnd,
            )
            ChoiceRow(
                selected = reading.end != null,
                enabled = enabled,
                title = "Bis zu einer Stelle",
                detail = if (reading.end == null) {
                    "Tippe im Text auf den Satz bzw. die Zeile, bis zu der du gekommen bist."
                } else {
                    "Gelesen ${describeEnd(reading.end)} – nächstes Mal geht es dort weiter."
                },
                onClick = onChoosePosition,
            )
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Der Text aus der Bibliothek: Rest zum Weiterlesen, mit Fortschritt; anderer Text oder entfernen. */
@Composable
private fun LibraryReadingCard(
    reading: LibraryReading,
    busy: Boolean,
    enabled: Boolean,
    reader: ReaderSettings,
    marks: ReadingMarks,
    documentListState: LazyListState,
    documentContent: DocumentContent?,
    onExpand: () -> Unit,
    onPickOther: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.LibraryBooks, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Aus der Bibliothek", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(reading.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            when {
                reading.again -> "Schon gelesen – du liest ihn noch einmal von vorne."
                reading.start != null -> "Es geht weiter, wo du aufgehört hast; blass ist das zuletzt Gelesene."
                else -> "Tippe beim oder nach dem Lesen auf die Stelle, bis zu der du kommst."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AttachmentPreview(
            attachment = reading.attachment,
            reader = reader,
            onExpand = onExpand,
            onOpenExternally = null,
            documentListState = documentListState,
            documentContent = documentContent,
            marks = marks,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPickOther, enabled = enabled && !busy) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Anderer Text")
            }
            TextButton(onClick = onRemove, enabled = enabled && !busy) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Entfernen")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** „bis zum Ende“, „bis „…Satzende““ oder „bis Seite 3“. */
private fun describeEnd(end: ReadingPosition?, maxAnchor: Int = 50): String = when (end) {
    null -> "bis zum Ende"
    is ReadingPosition.InText -> {
        val tail = end.anchor.takeLast(maxAnchor)
        "bis „${if (tail.length < end.anchor.length) "…$tail" else tail}“"
    }
    is ReadingPosition.OnPage -> "bis Seite ${end.page + 1}"
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
                    "Auf dem Handy sind nur noch $free frei. Damit Android und die App sicher weiterlaufen, " +
                        "nimmt die App erst ab etwa ${formatBytes(FileStore.RESERVE_BYTES + FileStore.AUDIO_BYTES_PER_MINUTE)} " +
                        "freiem Speicher auf. Gib Speicher frei, z. B. indem du Videos auslagerst oder Apps entfernst, " +
                        "die du nicht brauchst.",
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
                    if (state.micSilenced) {
                        Text(
                            "Eine andere App benutzt gerade das Mikrofon – aufgenommen wird nur Stille.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
