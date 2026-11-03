package de.emilybutterfly.stimmtraining.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import de.emilybutterfly.stimmtraining.data.TextAttachment
import java.io.File
import kotlin.math.min

/**
 * Was auf einer Seite (Foto oder PDF) markiert ist, jeweils als Anteil der Seitenhöhe
 * (0 = oben, 1 = unten).
 */
@Immutable
data class PageMarks(
    /** Darüber ausgegraut, mit Linie: schon früher gelesen oder nicht Teil des Abschnitts. */
    val dimAbove: Float? = null,
    /** Darunter ausgegraut, mit Linie: nicht mehr Teil des Abschnitts. */
    val dimBelow: Float? = null,
    /** In dieser Sitzung gelesen: farbig hinterlegt. */
    val readFrom: Float? = null,
    val readTo: Float? = null,
    /** Die angetippte Stelle „bis hier gelesen“. */
    val mark: Float? = null,
) {
    companion object {
        /** Die Abschnittslinien einer gespeicherten Aufnahme: oben auf der ersten, unten auf der letzten Seite. */
        fun section(page: Int, pageCount: Int, startY: Float?, endY: Float?): PageMarks? {
            val above = startY?.takeIf { page == 0 && it > 0f }
            val below = endY?.takeIf { page == pageCount - 1 && it < 1f }
            return if (above == null && below == null) null else PageMarks(dimAbove = above, dimBelow = below)
        }
    }
}

/** Farben für [drawPageMarks]; aus dem Farbschema, damit sie im Hell- und Dunkelmodus passen. */
@Immutable
class PageMarkColors(val dim: Color, val line: Color, val read: Color)

@Composable
fun pageMarkColors(): PageMarkColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        PageMarkColors(dim = scheme.scrim.copy(alpha = 0.45f), line = scheme.primary, read = scheme.primary.copy(alpha = 0.16f))
    }
}

/** Zeichnet [marks] in [page], dem Bereich, den die Seite auf dem Bildschirm einnimmt. */
fun DrawScope.drawPageMarks(marks: PageMarks, page: Rect, colors: PageMarkColors) {
    fun y(fraction: Float) = page.top + page.height * fraction.coerceIn(0f, 1f)
    val stroke = 3.dp.toPx()
    val readFrom = marks.readFrom
    val readTo = marks.readTo
    if (readFrom != null && readTo != null && readTo > readFrom) {
        drawRect(colors.read, Offset(page.left, y(readFrom)), Size(page.width, y(readTo) - y(readFrom)))
    }
    marks.dimAbove?.let { above ->
        drawRect(colors.dim, Offset(page.left, page.top), Size(page.width, y(above) - page.top))
        drawLine(colors.line, Offset(page.left, y(above)), Offset(page.right, y(above)), strokeWidth = stroke / 2)
    }
    marks.dimBelow?.let { below ->
        drawRect(colors.dim, Offset(page.left, y(below)), Size(page.width, page.bottom - y(below)))
        drawLine(colors.line, Offset(page.left, y(below)), Offset(page.right, y(below)), strokeWidth = stroke / 2)
    }
    marks.mark?.let { mark ->
        val at = y(mark)
        drawLine(colors.line, Offset(page.left, at), Offset(page.right, at), strokeWidth = stroke)
        // Eine Lasche am Rand, damit die Linie auch auf unruhigen Fotos auffällt.
        val tab = 10.dp.toPx()
        drawCircle(colors.line, radius = tab / 2, center = Offset(page.left + tab, at))
        drawCircle(colors.line, radius = tab / 2, center = Offset(page.right - tab, at))
    }
}

/** Der Bereich, den ein Bild der Größe [image] bei ContentScale.Fit in [container] einnimmt. */
internal fun fitRect(container: Size, image: Size?): Rect? {
    if (image == null || image.width <= 0f || image.height <= 0f || container.width <= 0f || container.height <= 0f) return null
    val scale = min(container.width / image.width, container.height / image.height)
    val width = image.width * scale
    val height = image.height * scale
    val left = (container.width - width) / 2
    val top = (container.height - height) / 2
    return Rect(left, top, left + width, top + height)
}

/**
 * Fotoseiten zum Durchwischen. Mit [zoomable] lässt sich jede Seite mit zwei Fingern oder
 * Doppeltipp vergrößern; vergrößert verschiebt ein Finger das Bild statt zu blättern.
 * [onTapPage] bekommt die angetippte Höhe (0..1) einer Seite, z. B. „bis hier gelesen“;
 * sonst löst ein Tipp [onClick] aus.
 */
@Composable
fun PhotoPager(
    pages: List<File>,
    modifier: Modifier = Modifier,
    zoomable: Boolean = true,
    onDark: Boolean = false,
    marks: (page: Int) -> PageMarks? = { null },
    onTapPage: ((page: Int, y: Float) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    state: PagerState = rememberPagerState { pages.size },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Box(modifier) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize(),
            key = { pages.getOrNull(it)?.path ?: it },
            beyondViewportPageCount = 1,
            contentPadding = contentPadding,
        ) { index ->
            val page = pages.getOrNull(index) ?: return@HorizontalPager
            ZoomableImage(
                photo = page,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                zoomable = zoomable,
                marks = marks(index),
                onTapY = onTapPage?.let { tap -> { y: Float -> tap(index, y) } },
                onClick = onClick,
                contentDescription = if (pages.size > 1) "Seite ${index + 1} von ${pages.size}" else "Foto des Textes",
            )
        }
        if (pages.size > 1) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (onDark) Color.Black.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            ) {
                Text(
                    "${state.currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Ein Foto, das sich mit zwei Fingern und per Doppeltipp vergrößern lässt. Unvergrößert
 * bleiben Wischgesten mit einem Finger frei, damit ein umgebender [PhotoPager] blättern kann.
 */
@Composable
fun ZoomableImage(
    photo: File,
    modifier: Modifier = Modifier,
    zoomable: Boolean = true,
    marks: PageMarks? = null,
    onTapY: ((Float) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    contentDescription: String = "Foto des Textes, vergrößerbar",
) {
    var scale by remember(photo) { mutableFloatStateOf(1f) }
    var offset by remember(photo) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(photo) { mutableStateOf<Size?>(null) }
    val colors = pageMarkColors()
    val currentOnTapY by rememberUpdatedState(onTapY)
    val currentOnClick by rememberUpdatedState(onClick)

    fun clamp(value: Offset, zoom: Float): Offset {
        val maxX = size.width * (zoom - 1) / 2f
        val maxY = size.height * (zoom - 1) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .semantics { this.contentDescription = contentDescription }
            .then(
                if (!zoomable) {
                    Modifier
                } else {
                    Modifier.pointerInput(photo) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                // Nur Zoomen oder ein vergrößertes Bild verschieben; sonst darf der Pager blättern.
                                if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
                                    val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                    scale = newScale
                                    offset = if (newScale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                },
            )
            .pointerInput(photo, zoomable) {
                detectTapGestures(
                    onDoubleTap = if (!zoomable) {
                        null
                    } else {
                        { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                scale = 2.5f
                                offset = clamp((center - tap) * 1.5f, 2.5f)
                            }
                        }
                    },
                    onTap = { tap ->
                        val onY = currentOnTapY
                        if (onY != null) {
                            // Vom Bildschirm zurück ins unvergrößerte Bild rechnen.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val content = center + (tap - offset - center) / scale
                            val rect = fitRect(Size(size.width.toFloat(), size.height.toFloat()), imageSize)
                            if (rect != null && content.y >= rect.top - 1 && content.y <= rect.bottom + 1) {
                                onY(((content.y - rect.top) / rect.height).coerceIn(0f, 1f))
                            }
                        } else {
                            currentOnClick?.invoke()
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .drawWithContent {
                    drawContent()
                    if (marks != null) fitRect(this.size, imageSize)?.let { drawPageMarks(marks, it, colors) }
                },
        ) {
            AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { imageSize = it.painter.intrinsicSize },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Vollbild zum Ordnen der Fotoseiten: verschieben, drehen, löschen und neue Seiten hinzufügen.
 * Mit [busy] (z. B. während eine Seite gedreht wird) ist nichts antippbar.
 */
@Composable
fun PageOverviewDialog(
    pages: List<File>,
    busy: Boolean,
    onRotate: (index: Int, clockwise: Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (index: Int) -> Unit,
    onAddCamera: (() -> Unit)?,
    onAddGallery: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        confirmDelete?.let { index ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(if (pages.size == 1) "Foto löschen?" else "Seite ${index + 1} löschen?") },
                text = {
                    Text(if (pages.size == 1) "Danach ist kein Text mehr angehängt." else "Die übrigen Seiten bleiben erhalten.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        onDelete(index)
                    }) { Text("Löschen") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
            )
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                    Text(
                        if (pages.size == 1) "1 Seite" else "${pages.size} Seiten",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(pages, key = { _, page -> page.path }) { index, page ->
                        PageRow(
                            page = page,
                            index = index,
                            count = pages.size,
                            enabled = !busy,
                            onRotate = { clockwise -> onRotate(index, clockwise) },
                            onMove = { delta -> onMove(index, index + delta) },
                            onDelete = { confirmDelete = index },
                        )
                    }
                    if (pages.size < TextAttachment.MAX_PAGES && (onAddCamera != null || onAddGallery != null)) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                onAddCamera?.let {
                                    OutlinedButton(onClick = it, enabled = !busy, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Fotografieren")
                                    }
                                }
                                onAddGallery?.let {
                                    OutlinedButton(onClick = it, enabled = !busy, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Aus Galerie")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageRow(
    page: File,
    index: Int,
    count: Int,
    enabled: Boolean,
    onRotate: (clockwise: Boolean) -> Unit,
    onMove: (delta: Int) -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = page,
                contentDescription = "Seite ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(72.dp, 96.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Seite ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
                FlowRow {
                    IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Seite ${index + 1} nach vorne")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Seite ${index + 1} nach hinten")
                    }
                    IconButton(onClick = { onRotate(false) }, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Seite ${index + 1} nach links drehen")
                    }
                    IconButton(onClick = { onRotate(true) }, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Seite ${index + 1} nach rechts drehen")
                    }
                    IconButton(onClick = onDelete, enabled = enabled) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Seite ${index + 1} löschen")
                    }
                }
            }
        }
    }
}

/** Nach einem Kamerafoto: gleich die nächste Seite fotografieren oder fertig sein. */
@Composable
fun NextPageDialog(pageCount: Int, onNextPage: () -> Unit, onDone: () -> Unit) {
    val full = pageCount >= TextAttachment.MAX_PAGES
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
        title = { Text(if (pageCount == 1) "Foto übernommen" else "Seite $pageCount übernommen") },
        text = {
            Text(
                if (full) {
                    "Mehr als ${TextAttachment.MAX_PAGES} Seiten passen nicht."
                } else {
                    "Hat der Text noch eine Seite? Dann fotografiere sie gleich."
                },
            )
        },
        confirmButton = { if (!full) TextButton(onClick = onNextPage) { Text("Nächste Seite") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Fertig") } },
    )
}
