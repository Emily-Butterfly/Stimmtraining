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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.LibraryAdd
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
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.AddPhotoAlternate
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
import androidx.compose.runtime.LaunchedEffect
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
 * [onCameraResult] wird aufgerufen, sobald dort ein Foto liegt – mit `addPage`, wenn es als
 * weitere Seite dazukommt statt den Text zu ersetzen; ebenso [onPhotosPicked] für Bilder aus der
 * Galerie. [onRotatePage], [onMovePage] und [onDeletePage] ändern einzelne Fotoseiten. [onTextPasted] bekommt den
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
    onCameraResult: (addPage: Boolean) -> Unit,
    onPhotosPicked: (uris: List<Uri>, addPage: Boolean) -> Unit,
    onRotatePage: (index: Int, clockwise: Boolean) -> Unit,
    onMovePage: (from: Int, to: Int) -> Unit,
    onDeletePage: (index: Int) -> Unit,
    onDocumentPicked: (Uri) -> Unit,
    onTextPasted: (text: String, name: String?) -> Unit,
    onRemove: () -> Unit,
    onExpand: () -> Unit,
    onOpenExternally: (TextAttachment.Document) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    documentListState: LazyListState? = null,
    documentContent: DocumentContent? = null,
    onPickFromLibrary: (() -> Unit)? = null,
    onAddToLibrary: (() -> Unit)? = null,
    /** false: Der Text lässt sich ersetzen, aber nicht ganz entfernen (z. B. in der Bibliothek). */
    removable: Boolean = true,
) {
    val context = LocalContext.current
    val canChange = enabled && !busy
    val photo = attachment as? TextAttachment.Photo
    val pageCount = photo?.pages?.size ?: 0
    // Gespeichert, weil Android die App beenden kann, während Kamera oder Galerie offen sind.
    var addingPage by rememberSaveable { mutableStateOf(false) }
    // Nach einem Kamerafoto fragen, ob noch eine Seite kommt – erst wenn es übernommen ist,
    // erkennbar an einer neuen letzten Seite. Scheitert die Übernahme, wird nicht gefragt.
    var awaitingCamera by rememberSaveable { mutableStateOf(false) }
    var sawImport by rememberSaveable { mutableStateOf(false) }
    var lastPageBeforeCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var askNextPage by rememberSaveable { mutableStateOf(false) }
    var showPages by rememberSaveable { mutableStateOf(false) }
    val lastPage = photo?.pages?.lastOrNull()?.path
    // Erst fragen, wenn nichts mehr läuft: Beim Bearbeiten werden gespeicherte Seiten vorher kopiert.
    LaunchedEffect(busy, lastPage, awaitingCamera) {
        if (!awaitingCamera) return@LaunchedEffect
        when {
            !busy && lastPage != null && lastPage != lastPageBeforeCamera -> {
                awaitingCamera = false
                sawImport = false
                askNextPage = true
            }
            busy -> sawImport = true
            sawImport -> {
                awaitingCamera = false
                sawImport = false
            }
        }
    }
    // Ohne Fotos gibt es keine Seiten mehr zu ordnen; sonst ginge die Übersicht beim nächsten Foto von selbst auf.
    LaunchedEffect(photo == null) {
        if (photo == null) showPages = false
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            lastPageBeforeCamera = lastPage
            sawImport = false
            awaitingCamera = true
            onCameraResult(addingPage)
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(TextAttachment.MAX_PAGES)) { uris ->
        if (uris.isNotEmpty()) onPhotosPicked(uris, addingPage)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onDocumentPicked(uri)
    }
    val openCamera = { addPage: Boolean ->
        addingPage = addPage && photo != null
        try {
            takePicture.launch(cameraTarget())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Keine Kamera-App gefunden.", Toast.LENGTH_SHORT).show()
        }
    }
    val openGallery = { addPage: Boolean ->
        addingPage = addPage && photo != null
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
    // Übernommener Text bleibt hier, bis er wirklich gespeichert ist (der Anhang wechselt).
    // Scheitert das Speichern, öffnen „Einfügen“ und „Bearbeiten“ ihn wieder.
    var pendingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val currentFile = attachment?.files?.firstOrNull()?.path.orEmpty()
    LaunchedEffect(currentFile) {
        if (pendingFor != null && pendingFor != currentFile) {
            pendingFor = null
            editorValue = TextFieldValue()
        }
    }
    val reopenPending = {
        val pending = pendingFor != null
        if (pending) {
            pendingFor = null
            editorOpen = true
        }
        pending
    }
    val pasteText = {
        if (!reopenPending()) {
            editorValue = TextFieldValue()
            editorName = null
            editorInitialHash = "".hashCode()
            editorOpen = true
        }
    }
    val editText = { document: TextAttachment.Document ->
        if (!reopenPending()) scope.launch {
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
                        FilledTonalButton(onClick = { openCamera(false) }, enabled = canChange, contentPadding = padding, modifier = Modifier.weight(1f)) {
                            ButtonLabel(Icons.Outlined.PhotoCamera, "Kamera")
                        }
                        OutlinedButton(onClick = { openGallery(false) }, enabled = canChange, contentPadding = padding, modifier = Modifier.weight(1f)) {
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
                    if (onPickFromLibrary != null) {
                        OutlinedButton(onClick = onPickFromLibrary, enabled = canChange, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
                            ButtonLabel(Icons.AutoMirrored.Outlined.LibraryBooks, "Aus Bibliothek")
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
                documentContent = documentContent,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (photo != null) {
                    TextButton(onClick = { showPages = true }, enabled = canChange) {
                        ButtonLabel(Icons.Outlined.ViewCarousel, if (pageCount == 1) "Drehen" else "Seiten ($pageCount)")
                    }
                    if (pageCount < TextAttachment.MAX_PAGES) {
                        var addMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { addMenu = true }, enabled = canChange) {
                                ButtonLabel(Icons.Outlined.AddPhotoAlternate, "Seite hinzufügen")
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Seite fotografieren") },
                                    leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                                    onClick = {
                                        addMenu = false
                                        openCamera(true)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Seiten aus der Galerie") },
                                    leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                    onClick = {
                                        addMenu = false
                                        openGallery(true)
                                    },
                                )
                            }
                        }
                    }
                }
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
                                openCamera(false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Bild aus der Galerie") },
                            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                            onClick = {
                                menu = false
                                openGallery(false)
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
                        if (onPickFromLibrary != null) {
                            DropdownMenuItem(
                                text = { Text("Aus der Bibliothek") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.LibraryBooks, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onPickFromLibrary()
                                },
                            )
                        }
                    }
                }
                if (attachment is TextAttachment.Document && attachment.kind == DocumentKind.PlainText) {
                    TextButton(onClick = { editText(attachment) }, enabled = canChange) {
                        ButtonLabel(Icons.Outlined.Edit, "Bearbeiten")
                    }
                }
                if (removable) {
                    TextButton(onClick = onRemove, enabled = canChange) {
                        ButtonLabel(Icons.Outlined.Delete, "Entfernen")
                    }
                }
                if (onAddToLibrary != null) {
                    TextButton(onClick = onAddToLibrary, enabled = canChange) {
                        ButtonLabel(Icons.Outlined.LibraryAdd, "In Bibliothek übernehmen")
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    if (askNextPage) {
        NextPageDialog(
            pageCount = pageCount,
            onNextPage = {
                askNextPage = false
                openCamera(true)
            },
            onDone = { askNextPage = false },
        )
    }

    if (showPages && photo != null) {
        PageOverviewDialog(
            pages = photo.pages,
            busy = busy,
            onRotate = onRotatePage,
            onMove = onMovePage,
            onDelete = onDeletePage,
            canDeleteLast = removable,
            onAddCamera = { openCamera(true) }.takeIf { enabled },
            onAddGallery = { openGallery(true) }.takeIf { enabled },
            onDismiss = { showPages = false },
        )
    }

    if (editorOpen) {
        TextEditorDialog(
            value = editorValue,
            onValueChange = { editorValue = it },
            changed = editorValue.text.isNotBlank() && editorValue.text.hashCode() != editorInitialHash,
            onConfirm = {
                onTextPasted(editorValue.text, editorName)
                editorOpen = false
                pendingFor = currentFile
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
    documentContent: DocumentContent? = null,
    marks: ReadingMarks? = null,
) {
    when (attachment) {
        is TextAttachment.Photo -> Box(modifier) {
            PhotoPages(
                photo = attachment,
                marks = marks,
                zoomable = false,
                onClick = onExpand,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Surface(
                onClick = onExpand,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Foto vergrößern", modifier = Modifier.padding(6.dp).size(20.dp))
            }
        }
        is TextAttachment.Document ->
            DocumentCard(attachment, reader, onExpand, onOpenExternally, modifier, documentListState, documentContent, marks)
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
    preloaded: DocumentContent? = null,
    marks: ReadingMarks? = null,
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
            val content = preloaded ?: rememberDocumentContent(document).value
            DocumentView(content, reader, Modifier.heightIn(max = 420.dp), listState = listState ?: rememberLazyListState(), marks = marks)
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
    documentContent: DocumentContent? = null,
    marks: ReadingMarks? = null,
) {
    when (attachment) {
        is TextAttachment.Photo -> PhotoPages(photo = attachment, marks = marks, modifier = modifier.clipToBounds())
        is TextAttachment.Document -> key(attachment.file) {
            val content = documentContent ?: rememberDocumentContent(attachment).value
            DocumentView(content, reader, modifier, listState = documentListState ?: rememberLazyListState(), marks = marks)
        }
    }
}

/** Vollbildansicht: Fotos mit Zoom auf Schwarz, Dateien als Leseansicht. */
@Composable
fun AttachmentViewer(attachment: TextAttachment, reader: ReaderSettings, onDismiss: () -> Unit) {
    when (attachment) {
        is TextAttachment.Photo -> PhotoViewer(attachment, onDismiss)
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

/**
 * Die Fotoseiten mit Abschnittslinien bzw. Lesefortschritt ([marks]): beim Weiterlesen ab der
 * Seite, auf der zuletzt aufgehört wurde; ein Tipp setzt dann die Stelle statt [onClick].
 */
@Composable
private fun PhotoPages(
    photo: TextAttachment.Photo,
    marks: ReadingMarks?,
    modifier: Modifier = Modifier,
    zoomable: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val first = marks?.firstPage(photo.pages.size) ?: 0
    val pages = photo.pages.drop(first)
    val onMark = marks?.onMark
    // Ein anderer Text (oder ein anderer Anfang) beginnt auf seiner ersten Seite, nicht dort, wo der vorige stand.
    key(photo.pages.firstOrNull(), first) {
        PhotoPager(
            pages = pages,
            zoomable = zoomable,
            marks = { page ->
                marks?.pageMarks(first + page) ?: PageMarks.section(first + page, photo.pages.size, photo.startY, photo.endY)
            },
            onTapPage = onMark?.let {
                { page: Int, y: Float -> marks.tapPage(first + page, photo.pages.size, y, pages.getOrNull(page)?.name) }
            },
            onClick = onClick.takeIf { onMark == null },
            modifier = modifier,
            pageOffset = first,
        )
    }
}

/** Vollbildansicht zum Durchwischen, mit Zwei-Finger-Zoom und Doppeltipp-Zoom. */
@Composable
fun PhotoViewer(photo: TextAttachment.Photo, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PhotoPager(
                pages = photo.pages,
                onDark = true,
                marks = { page -> PageMarks.section(page, photo.pages.size, photo.startY, photo.endY) },
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Schließen", tint = Color.White)
            }
        }
    }
}
