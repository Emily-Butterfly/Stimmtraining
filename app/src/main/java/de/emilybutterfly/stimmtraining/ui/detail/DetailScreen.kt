package de.emilybutterfly.stimmtraining.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.analysis.Notes
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.ui.components.AttachmentPreview
import de.emilybutterfly.stimmtraining.ui.components.AttachmentViewer
import de.emilybutterfly.stimmtraining.ui.components.AudioPlayerBar
import de.emilybutterfly.stimmtraining.ui.components.ParameterForm
import de.emilybutterfly.stimmtraining.ui.components.RatingDisplay
import de.emilybutterfly.stimmtraining.ui.components.ReaderSettings
import de.emilybutterfly.stimmtraining.ui.components.SELF_RATING_LABELS
import de.emilybutterfly.stimmtraining.ui.components.TextAttachmentSection
import de.emilybutterfly.stimmtraining.ui.components.VOICE_CONDITION_LABELS
import de.emilybutterfly.stimmtraining.ui.components.formatDateTime
import de.emilybutterfly.stimmtraining.ui.components.formatDuration
import de.emilybutterfly.stimmtraining.ui.components.rememberReaderSettings
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recordingId: Long,
    onClose: () -> Unit,
    viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory(recordingId)),
) {
    val load by viewModel.load.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pitchProgress by viewModel.pitchProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf(false) }
    val reader = rememberReaderSettings()
    val openExternally = { document: TextAttachment.Document ->
        viewModel.openExternally(document) { context.startActivity(it) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, duration = if (it.length > 80) SnackbarDuration.Long else SnackbarDuration.Short)
            viewModel.messageShown()
        }
    }
    BackHandler(enabled = edit != null) { viewModel.cancelEdit() }

    val recording = (load as? DetailLoad.Ready)?.recording
    val editing = edit

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editing != null) "Bearbeiten" else recording?.displayTitle.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (editing != null) {
                        IconButton(onClick = viewModel::cancelEdit) {
                            Icon(Icons.Filled.Close, contentDescription = "Abbrechen")
                        }
                    } else {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    if (editing != null) {
                        IconButton(onClick = viewModel::saveEdit, enabled = !editing.saving && !editing.attachmentBusy) {
                            Icon(Icons.Filled.Check, contentDescription = "Speichern")
                        }
                    } else if (recording != null) {
                        IconButton(onClick = { viewModel.share { context.startActivity(it) } }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Teilen")
                        }
                        IconButton(onClick = viewModel::startEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Bearbeiten")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Löschen")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when {
            load is DetailLoad.Loading -> Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            recording == null -> Box(contentModifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Diese Aufnahme gibt es nicht mehr.")
            }
            else -> Column(
                contentModifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (editing != null) {
                    Text(
                        "Aufgenommen ${formatDateTime(recording.recordedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextAttachmentSection(
                        attachment = viewModel.editedAttachment(recording, editing),
                        busy = editing.attachmentBusy,
                        reader = reader,
                        cameraTarget = viewModel::cameraTarget,
                        onCameraResult = viewModel::onCameraPhoto,
                        onPhotoPicked = viewModel::onPhotoPicked,
                        onDocumentPicked = viewModel::onDocumentPicked,
                        onTextPasted = viewModel::onTextPasted,
                        onRemove = viewModel::removeAttachment,
                        onExpand = { viewing = true },
                        onOpenExternally = openExternally,
                    )
                    ParameterForm(params = editing.params, onChange = viewModel::updateParams)
                    Button(
                        onClick = viewModel::saveEdit,
                        enabled = !editing.saving && !editing.attachmentBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Änderungen speichern") }
                } else {
                    RecordingDetails(
                        recording = recording,
                        audio = viewModel.audioFile(recording),
                        attachment = viewModel.attachment(recording),
                        pitchProgress = pitchProgress,
                        onAnalyzePitch = viewModel::analyzePitch,
                        reader = reader,
                        onExpand = { viewing = true },
                        onOpenExternally = openExternally,
                    )
                }
            }
        }
    }

    val shown = recording?.let { if (editing != null) viewModel.editedAttachment(it, editing) else viewModel.attachment(it) }
    if (viewing && shown != null) AttachmentViewer(shown, reader, onDismiss = { viewing = false })

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Aufnahme löschen?") },
            text = { Text("Audio, Text (Foto oder Datei) und Angaben werden endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted = onClose)
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun RecordingDetails(
    recording: Recording,
    audio: File,
    attachment: TextAttachment?,
    pitchProgress: Float?,
    onAnalyzePitch: () -> Unit,
    reader: ReaderSettings,
    onExpand: () -> Unit,
    onOpenExternally: (TextAttachment.Document) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                formatDateTime(recording.recordedAt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            AudioPlayerBar(audio, Modifier.fillMaxWidth())
        }
    }

    PitchCard(recording, pitchProgress, onAnalyzePitch)

    if (attachment != null) {
        AttachmentPreview(attachment, reader, onExpand = onExpand, onOpenExternally = onOpenExternally)
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            DetailRow("Dauer", formatDuration(recording.durationMs))
            HorizontalDivider()
            DetailRow(
                "Aufgewärmt",
                when {
                    !recording.warmedUp -> "Nein"
                    recording.warmUpMinutes != null -> "Ja, ${recording.warmUpMinutes} Minuten"
                    else -> "Ja"
                },
            )
            HorizontalDivider()
            DetailRow("Übung", recording.exercise.ifBlank { "–" })
            if (recording.title.isNotBlank()) {
                HorizontalDivider()
                DetailRow("Titel / Text", recording.title)
            }
            HorizontalDivider()
            RatingRow("Stimmliche Tagesform", recording.voiceCondition, VOICE_CONDITION_LABELS)
            HorizontalDivider()
            RatingRow("Zufriedenheit", recording.selfRating, SELF_RATING_LABELS)
        }
    }

    if (recording.notes.isNotBlank()) {
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Notizen", style = MaterialTheme.typography.titleSmall)
                Text(recording.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Häufigste, tiefste und höchste Note der Aufnahme – oder der Knopf, sie zu ermitteln. */
@Composable
private fun PitchCard(recording: Recording, progress: Float?, onAnalyze: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Tonhöhe", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (recording.pitchVoicedMs != null && progress == null) {
                    TextButton(onClick = onAnalyze) { Text("Neu analysieren") }
                }
            }
            val most = recording.pitchMostFrequent
            val lowest = recording.pitchLowest
            val highest = recording.pitchHighest
            when {
                progress != null -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Die Tonhöhe wird analysiert …", style = MaterialTheme.typography.bodySmall)
                }
                recording.pitchVoicedMs == null -> {
                    Text(
                        "Ermittelt, welche Note du am häufigsten sprichst, und die tiefste und höchste Note. " +
                            "Die Analyse läuft nur auf diesem Handy – die Aufnahme verlässt das Gerät nicht.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) { Text("Tonhöhe analysieren") }
                }
                most == null || lowest == null || highest == null -> Text(
                    "In dieser Aufnahme wurde zu wenig Stimme erkannt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoteTile("Tiefste", lowest, Modifier.weight(1f))
                        NoteTile("Häufigste", most, Modifier.weight(1f), highlight = true)
                        NoteTile("Höchste", highest, Modifier.weight(1f))
                    }
                    Text(
                        "Ausgewertet: ${formatSpokenTime(recording.pitchVoicedMs)} Stimme. Kurze Ausreißer zählen nicht.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteTile(label: String, midi: Int, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(Notes.name(midi), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${Notes.frequency(midi).roundToInt()} Hz", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatSpokenTime(millis: Long): String =
    if (millis < 60_000) "${(millis + 500) / 1000} s" else "${formatDuration(millis)} min"

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun RatingRow(label: String, value: Int?, labels: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Column(Modifier.weight(0.55f)) {
            if (value == null) {
                Text("–", style = MaterialTheme.typography.bodyMedium)
            } else {
                RatingDisplay(value)
                Text(labels[value - 1], style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
