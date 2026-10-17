package de.emilybutterfly.stimmtraining.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import de.emilybutterfly.stimmtraining.data.PastedText
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Der Text, den man beim Aufnehmen liest: als Foto (Kamera oder Galerie), als Datei
 * (PDF, Word, OpenDocument, Text, …) oder eingefügt aus der Zwischenablage. Anzeigen,
 * ersetzen, bearbeiten (eingefügte Texte und Textdateien) oder entfernen.
 *
 * [cameraTarget] liefert die Content-URI, in die die Kamera-App schreiben soll;
 * [onCameraResult] wird aufgerufen, sobald dort ein Foto liegt. [onTextPasted] bekommt den
 * Text und den Namen, unter dem er erscheinen soll (null: aus der ersten Zeile). [onExpand] öffnet den Text
 * groß zum Lesen. Mit [enabled] = false lässt sich nichts ändern und keine andere App öffnen
 * (z. B. während einer Aufnahme, die sonst im Hintergrund enden würde); lesen geht weiter.
 */
@Composable
fun TextAttachmentSection(
    attachment: TextAttachment?,
    busy: Boolean,
    reader: ReaderSettings,
    cameraTarget: () -> Uri,
    onCameraResult: () -> Unit,
    onPhotoPicked: (Uri) -> Unit,
    onDocumentPicked: (Uri) -> Unit,
    onTextPasted: (text: String, name: String?) -> Unit,
    onRemove: () -> Unit,
    onExpand: () -> Unit,
    onOpenExternally: (TextAttachment.Document) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    documentListState: LazyListState? = null,
) {
    val context = LocalContext.current
    val canChange = enabled && !busy
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) onCameraResult()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhotoPicked(uri)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onDocumentPicked(uri)
    }
    val openCamera = {
        try {
            takePicture.launch(cameraTarget())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Keine Kamera-App gefunden.", Toast.LENGTH_SHORT).show()
        }
    }
    val openGallery = {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openFile = {
        try {
            pickDocument.launch(DocumentKind.PICKER_MIME_TYPES)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Keine App zur Dateiauswahl gefunden.", Toast.LENGTH_SHORT).show()
        }
    }

    // Der Text im Editor übersteht auch, dass Android die App im Hintergrund beendet.
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var editorName by rememberSaveable { mutableStateOf<String?>(null) }
    // Nur ein Prüfwert des Ausgangstextes: Der Text selbst steckt schon in editorValue.
    var editorInitialHash by rememberSaveable { mutableStateOf("".hashCode()) }
    val scope = rememberCoroutineScope()
    val pasteText = {
        editorValue = TextFieldValue()
        editorName = null
        editorInitialHash = "".hashCode()
        editorOpen = true
    }
    val editText = { document: TextAttachment.Document ->
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { PastedText.readForEditing(document.file) }.getOrNull() }
            if (text == null) {
                Toast.makeText(context, "Diese Datei lässt sich hier nicht bearbeiten.", Toast.LENGTH_SHORT).show()
            } else {
                editorValue = TextFieldValue(text, TextRange(text.length))
                editorInitialHash = text.hashCode()
                // Ein aus der ersten Zeile gebildeter Name passt sich dem neuen Text an.
                editorName = document.name.takeIf { it != PastedText.name(text) }
                editorOpen = true
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (attachment == null) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Text zum Lesen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (enabled) {
                            "Fotografiere den Text, wähle ein Bild oder eine Datei (PDF, Word, OpenDocument, Text, " +
                                "Markdown) oder füge ihn ein – er wird dir dann beim Aufnehmen angezeigt."
                        } else {
                            "Während der Aufnahme lässt sich kein Text hinzufügen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = openCamera, enabled = canChange, contentPadding = padding, modifier = Modifier.weight(1f)) {
                            ButtonLabel(Icons.Outlined.PhotoCamera, "Kamera")
                        }
                        OutlinedButton(onClick = openGallery, enabled = canChange, contentPadding = padding, modifier = Modifier.weight(1f)) {
                            ButtonLabel(Icons.Outlined.Image, "Galerie")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openFile, enabled = canChange, contentPadding = padding, modifier = Modifier.weight(1f)) {
                            ButtonLabel(Icons.Outlined.Description, "Datei")
                        }
                        OutlinedButton(onClick = pasteText, enabled = canChange, contentPadding = padding, modifier = Modifier.weight(1f)) {
                            ButtonLabel(Icons.Outlined.ContentPaste, "Einfügen")
                        }
                    }
                }
            }
        } else {
            AttachmentPreview(
                attachment = attachment,
                reader = reader,
                onExpand = onExpand,
                onOpenExternally = onOpenExternally.takeIf { enabled },
                documentListState = documentListState,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }, enabled = canChange) {
                        ButtonLabel(Icons.Outlined.SwapHoriz, "Ersetzen")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Foto aufnehmen") },
                            leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                            onClick = {
                                menu = false
                                openCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Bild aus der Galerie") },
                            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                            onClick = {
                                menu = false
                                openGallery()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Datei (PDF, Word, Text …)") },
                            leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                            onClick = {
                                menu = false
                                openFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Text einfügen") },
                            leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                            onClick = {
                                menu = false
                                pasteText()
                            },
                        )
                    }
                }
                if (attachment is TextAttachment.Document && attachment.kind == DocumentKind.PlainText) {
                    TextButton(onClick = { editText(attachment) }, enabled = canChange) {
                        ButtonLabel(Icons.Outlined.Edit, "Bearbeiten")
                    }
                }
                TextButton(onClick = onRemove, enabled = canChange) {
                    ButtonLabel(Icons.Outlined.Delete, "Entfernen")
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    if (editorOpen) {
        TextEditorDialog(
            value = editorValue,
            onValueChange = { editorValue = it },
            changed = editorValue.text.isNotBlank() && editorValue.text.hashCode() != editorInitialHash,
            onConfirm = {
                onTextPasted(editorValue.text, editorName)
                editorOpen = false
                editorValue = TextFieldValue()
            },
            onDismiss = {
                editorOpen = false
                editorValue = TextFieldValue()
            },
        )
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(4.dp))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Vorschau des Textes: das Foto oder die Datei, mit Knopf zum Vergrößern. Ist
 * [onOpenExternally] gesetzt, lässt sich eine Datei auch in einer anderen App öffnen.
 * Mit [documentListState] behält eine Datei ihre Leseposition, z. B. beim Wechsel in den
 * Lesemodus; der Zustand darf nur von einer gleichzeitig sichtbaren Ansicht genutzt werden.
 */
@Composable
fun AttachmentPreview(
    attachment: TextAttachment,
    reader: ReaderSettings,
    onExpand: () -> Unit,
    onOpenExternally: ((TextAttachment.Document) -> Unit)?,
    modifier: Modifier = Modifier,
    documentListState: LazyListState? = null,
) {
    when (attachment) {
        is TextAttachment.Photo -> Box(modifier) {
            AsyncImage(
                model = attachment.file,
                contentDescription = "Foto des Textes",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Foto vergrößern", onClick = onExpand),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.ZoomIn, contentDescription = null, modifier = Modifier.padding(6.dp).size(20.dp))
            }
        }
        is TextAttachment.Document -> DocumentCard(attachment, reader, onExpand, onOpenExternally, modifier, documentListState)
    }
}

@Composable
private fun DocumentCard(
    document: TextAttachment.Document,
    reader: ReaderSettings,
    onExpand: () -> Unit,
    onOpenExternally: ((TextAttachment.Document) -> Unit)?,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
) {
    val kind = document.kind
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(documentIcon(kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(document.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ReaderSizeButtons(reader, kind)
            IconButton(onClick = onExpand) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "Groß anzeigen")
            }
        }
        HorizontalDivider()
        // Eine neue Datei beginnt mit „lädt …“ und oben, nicht mit Inhalt und Position der alten.
        key(document.file) {
            val content by rememberDocumentContent(document)
            DocumentView(content, reader, Modifier.heightIn(max = 420.dp), listState = listState ?: rememberLazyListState())
        }
        if (onOpenExternally != null) {
            HorizontalDivider()
            TextButton(onClick = { onOpenExternally(document) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                ButtonLabel(Icons.AutoMirrored.Outlined.OpenInNew, "In anderer App öffnen")
            }
        }
    }
}

fun documentIcon(kind: DocumentKind): ImageVector =
    if (kind == DocumentKind.Pdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description

/** Der Text so groß wie der verfügbare Platz, z. B. im Lesemodus beim Aufnehmen. */
@Composable
fun AttachmentReader(
    attachment: TextAttachment,
    reader: ReaderSettings,
    modifier: Modifier = Modifier,
    documentListState: LazyListState? = null,
) {
    when (attachment) {
        is TextAttachment.Photo -> ZoomableImage(attachment.file, modifier.clipToBounds())
        is TextAttachment.Document -> key(attachment.file) {
            val content by rememberDocumentContent(attachment)
            DocumentView(content, reader, modifier, listState = documentListState ?: rememberLazyListState())
        }
    }
}

/** Vollbildansicht: Fotos mit Zoom auf Schwarz, Dateien als Leseansicht. */
@Composable
fun AttachmentViewer(attachment: TextAttachment, reader: ReaderSettings, onDismiss: () -> Unit) {
    when (attachment) {
        is TextAttachment.Photo -> PhotoViewer(attachment.file, onDismiss)
        is TextAttachment.Document -> Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding()) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Schließen")
                        }
                        Text(
                            attachment.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ReaderSizeButtons(reader, attachment.kind)
                    }
                    HorizontalDivider()
                    AttachmentReader(attachment, reader, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** Vollbildansicht mit Zwei-Finger-Zoom und Doppeltipp-Zoom. */
@Composable
fun PhotoViewer(photo: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableImage(photo, Modifier.fillMaxSize())
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Schließen", tint = Color.White)
            }
        }
    }
}

@Composable
fun ZoomableImage(photo: File, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(value: Offset, zoom: Float): Offset {
        val maxX = size.width * (zoom - 1) / 2f
        val maxY = size.height * (zoom - 1) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    AsyncImage(
        model = photo,
        contentDescription = "Foto des Textes, vergrößerbar",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = 2.5f
                            offset = clamp((center - tap) * 1.5f, 2.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = newScale
                    offset = if (newScale == 1f) Offset.Zero else clamp(offset + pan, newScale)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
