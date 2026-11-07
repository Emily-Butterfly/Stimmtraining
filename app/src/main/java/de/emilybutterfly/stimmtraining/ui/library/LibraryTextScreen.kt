package de.emilybutterfly.stimmtraining.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.LibraryText
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.ui.components.AttachmentPreview
import de.emilybutterfly.stimmtraining.ui.components.AttachmentReader
import de.emilybutterfly.stimmtraining.ui.components.ReaderSettings
import de.emilybutterfly.stimmtraining.ui.components.ReaderSizeButtons
import de.emilybutterfly.stimmtraining.ui.components.ReadingMarks
import de.emilybutterfly.stimmtraining.ui.components.AttachmentViewer
import de.emilybutterfly.stimmtraining.ui.components.TextAttachmentSection
import de.emilybutterfly.stimmtraining.ui.components.rememberReaderSettings

/**
 * Ein Text der Bibliothek ([textId]) oder, mit null, das Anlegen eines neuen. [onRecord] startet
 * eine Aufnahme mit diesem Text; [onMarkPosition] öffnet die Ansicht zum Antippen der gelesenen Stelle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTextScreen(
    textId: Long?,
    onClose: () -> Unit,
    onRecord: (Long) -> Unit,
    viewModel: LibraryTextViewModel = viewModel(factory = LibraryTextViewModel.factory(textId)),
) {
    val load by viewModel.load.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val editorState by viewModel.editor.state.collectAsStateWithLifecycle()
    val knownTags by viewModel.knownTags.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val reader = rememberReaderSettings()
    var viewing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var markingPosition by rememberSaveable { mutableStateOf(false) }

    // Neu angelegt: zurück zur Liste – mit dem Rückruf dieser Ansicht, auch nach dem Drehen.
    val created by viewModel.created.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(created) {
        if (created != null) currentOnClose()
    }
    // Gelöscht (hier oder anderswo): Es gibt nichts mehr zu zeigen.
    LaunchedEffect(load) {
        if (load is LibraryLoad.Missing) currentOnClose()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, duration = if (it.length > 80) SnackbarDuration.Long else SnackbarDuration.Short)
            viewModel.messageShown()
        }
    }

    val text = (load as? LibraryLoad.Ready)?.text
    val editing = edit
    val leaveEdit = {
        when {
            editing?.saving == true -> Unit
            viewModel.hasChanges() -> confirmDiscard = true
            viewModel.isNew -> {
                viewModel.cancelEdit()
                onClose()
            }
            else -> viewModel.cancelEdit()
        }
    }
    BackHandler(enabled = editing != null) { leaveEdit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            viewModel.isNew -> "Neuer Text"
                            editing != null -> "Bearbeiten"
                            else -> text?.title.orEmpty()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (editing != null) {
                        IconButton(onClick = leaveEdit) { Icon(Icons.Filled.Close, contentDescription = "Abbrechen") }
                    } else {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                    }
                },
                actions = {
                    if (editing != null) {
                        IconButton(onClick = { viewModel.save() }, enabled = !editing.saving && !editorState.busy) {
                            Icon(Icons.Filled.Check, contentDescription = "Speichern")
                        }
                    } else if (text != null) {
                        if (!text.isFinished) {
                            IconButton(onClick = viewModel::togglePinned) {
                                Icon(
                                    if (text.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = if (text.pinned) "Nicht mehr anheften" else "Anheften",
                                )
                            }
                        }
                        IconButton(onClick = viewModel::startEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Bearbeiten") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Löschen") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        when {
            editing != null -> Column(
                modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val editor = viewModel.editor
                TextAttachmentSection(
                    attachment = viewModel.shownAttachment(),
                    busy = editorState.busy,
                    reader = reader,
                    cameraTarget = viewModel::cameraTarget,
                    onCameraResult = editor::onCameraPhoto,
                    onPhotosPicked = editor::onPhotosPicked,
                    onRotatePage = editor::rotatePage,
                    onMovePage = editor::movePage,
                    onDeletePage = editor::deletePage,
                    onDocumentPicked = editor::onDocumentPicked,
                    onTextPasted = editor::onTextPasted,
                    onRemove = editor::remove,
                    removable = viewModel.isNew,
                    enabled = !editing.saving,
                    onExpand = { viewing = true },
                    onOpenExternally = { document -> viewModel.openExternally(document) { context.startActivity(it) } },
                )
                OutlinedTextField(
                    value = editing.title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text("Titel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TagEditor(
                    tags = editing.tags,
                    known = knownTags,
                    input = editing.tagInput,
                    onInputChange = viewModel::updateTagInput,
                    onAddInput = viewModel::addTagInput,
                    onAdd = viewModel::addTag,
                    onRemove = viewModel::removeTag,
                )
                Button(
                    onClick = { viewModel.save() },
                    enabled = !editing.saving && !editorState.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (viewModel.isNew) "In die Bibliothek legen" else "Änderungen speichern") }
            }
            load is LibraryLoad.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            text == null -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Diesen Text gibt es nicht mehr.")
            }
            else -> Column(
                modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProgressCard(
                    text = text,
                    onRecord = { onRecord(text.id) },
                    onMarkPosition = { markingPosition = true },
                    onRestart = { confirmRestart = true },
                    onReadAgain = viewModel::readAgain,
                )
                if (text.tagList.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        text.tagList.forEach { TagLabel(it) }
                    }
                }
                val attachment = viewModel.attachment(text)
                if (attachment == null) {
                    Text("Die Datei zu diesem Text fehlt.", color = MaterialTheme.colorScheme.error)
                } else {
                    AttachmentPreview(
                        attachment = attachment,
                        reader = reader,
                        onExpand = { viewing = true },
                        onOpenExternally = { document -> viewModel.openExternally(document) { context.startActivity(it) } },
                        // Schon Gelesenes ist blass, damit man sieht, wo es weitergeht.
                        marks = text.progress?.let { ReadingMarks(start = it, showAll = true) },
                    )
                }
            }
        }
    }

    val shown = if (editing != null) viewModel.shownAttachment() else text?.let(viewModel::attachment)
    if (viewing && shown != null) AttachmentViewer(shown, reader, onDismiss = { viewing = false })

    if (markingPosition && text != null) {
        val attachment = viewModel.attachment(text)
        if (attachment != null) {
            PositionMarker(
                text = text,
                attachment = attachment,
                reader = reader,
                onSave = { position ->
                    viewModel.setProgress(position)
                    markingPosition = false
                },
                onDismiss = { markingPosition = false },
            )
        }
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Von vorne beginnen?") },
            text = { Text("Die Stelle, bis zu der du gelesen hast, wird vergessen. Deine Aufnahmen bleiben, wie sie sind.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    viewModel.setProgress(null)
                }) { Text("Von vorne") }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Abbrechen") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Text löschen?") },
            text = { Text("Der Text verschwindet aus der Bibliothek. Aufnahmen, in denen du ihn gelesen hast, behalten ihren Abschnitt.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Änderungen verwerfen?") },
            text = { Text(if (viewModel.isNew) "Der Text wird nicht in die Bibliothek gelegt." else "Deine Änderungen werden nicht gespeichert.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.cancelEdit()
                    if (viewModel.isNew) onClose()
                }) { Text("Verwerfen") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } },
        )
    }
}

/** Wie weit der Text gelesen ist, mit den passenden Knöpfen. */
@Composable
private fun ProgressCard(
    text: LibraryText,
    onRecord: () -> Unit,
    onMarkPosition: () -> Unit,
    onRestart: () -> Unit,
    onReadAgain: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(progressHeadline(text), style = MaterialTheme.typography.titleSmall)
            progressDetail(text)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val fraction = text.progressFraction
            if (!text.isFinished && fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        text.isFinished -> "Noch einmal von vorne aufnehmen"
                        text.progress != null -> "Weiterlesen und aufnehmen"
                        else -> "Für eine Aufnahme verwenden"
                    },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text.isFinished) {
                    OutlinedButton(onClick = onReadAgain) {
                        Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nochmal lesen")
                    }
                } else {
                    OutlinedButton(onClick = onMarkPosition) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stelle ändern")
                    }
                    if (text.progress != null) {
                        OutlinedButton(onClick = onRestart) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Von vorne beginnen")
                        }
                    }
                }
            }
        }
    }
}

private fun progressHeadline(text: LibraryText): String = when {
    text.isFinished -> progressLabel(text, null).substringAfter(" · ")
    text.progress != null -> text.progressFraction?.let { "Etwa ${(it * 100).toInt().coerceIn(1, 99)} % gelesen" } ?: "Angefangen"
    else -> "Noch nicht begonnen"
}

private fun progressDetail(text: LibraryText): String? = when (val position = text.progress) {
    is ReadingPosition.InText -> "Zuletzt gelesen: „${position.anchor.takeLast(120).let { if (it.length < position.anchor.length) "…$it" else it }}“"
    is ReadingPosition.OnPage -> "Gelesen bis Seite ${position.page + 1}."
    null -> if (text.isFinished) "Mit „Nochmal lesen“ kommt er zurück unter „Zu lesen“ und beginnt von vorne." else null
}

/** Schlagworte hinzufügen (mit Vorschlägen aus schon benutzten) und entfernen. */
@Composable
private fun TagEditor(
    tags: List<String>,
    known: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onAddInput: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val add = onAddInput
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Schlagworte", style = MaterialTheme.typography.titleSmall)
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(tag) },
                        label = { Text(tag) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "$tag entfernen", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text("z. B. Gedicht, Zeitung, Dialog") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                IconButton(onClick = add, enabled = input.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Schlagwort hinzufügen") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = known
            .filter { candidate -> tags.none { it.equals(candidate, ignoreCase = true) } }
            .filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }
            .take(12)
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { suggestion ->
                    AssistChip(onClick = {
                        onAdd(suggestion)
                        onInputChange("")
                    }, label = { Text(suggestion) }, leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) })
                }
            }
        }
    }
}

/**
 * Vollbild zum Korrigieren der gelesenen Stelle: Der ganze Text ist zu sehen, das Gelesene farbig;
 * ein Tipp auf einen Satz (oder eine Zeile auf der Seite) setzt die neue Stelle.
 */
@Composable
private fun PositionMarker(
    text: LibraryText,
    attachment: TextAttachment,
    reader: ReaderSettings,
    onSave: (ReadingPosition?) -> Unit,
    onDismiss: () -> Unit,
) {
    var candidate by remember(text.id) { mutableStateOf(text.progress) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Abbrechen") }
                    Text("Bis wohin gelesen?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (attachment is TextAttachment.Document) ReaderSizeButtons(reader, attachment.kind)
                    TextButton(onClick = { onSave(candidate) }, enabled = candidate != text.progress) { Text("Übernehmen") }
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Tippe auf den Satz bzw. die Zeile, bis zu der du gelesen hast. Farbig ist, was als gelesen gilt.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                AttachmentReader(
                    attachment = attachment,
                    reader = reader,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    marks = ReadingMarks(end = candidate, onMark = { candidate = it }),
                )
            }
        }
    }
}
