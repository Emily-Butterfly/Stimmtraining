package de.emilybutterfly.stimmtraining.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.document.BlockStyle
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import de.emilybutterfly.stimmtraining.data.document.DocumentText
import de.emilybutterfly.stimmtraining.data.document.PdfPages
import de.emilybutterfly.stimmtraining.data.document.TextBlock
import de.emilybutterfly.stimmtraining.data.document.TextDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.ceil

/** Inhalt eines Dokuments, fertig zum Anzeigen. */
sealed interface DocumentContent {
    data object Loading : DocumentContent
    data class Text(val document: TextDocument) : DocumentContent
    data class Pdf(val pages: PdfPages) : DocumentContent
    data class Failed(val message: String) : DocumentContent
}

/** Lädt das Dokument im Hintergrund; ein geöffnetes PDF wird wieder geschlossen, wenn es nicht mehr angezeigt wird. */
@Composable
fun rememberDocumentContent(document: TextAttachment.Document): State<DocumentContent> =
    produceState<DocumentContent>(DocumentContent.Loading, document.file) {
        var pages: PdfPages? = null
        try {
            value = withContext(Dispatchers.IO) {
                when (val kind = document.kind) {
                    DocumentKind.Pdf -> DocumentContent.Pdf(PdfPages.open(document.file).also { pages = it })
                    else -> DocumentContent.Text(DocumentText.read(document.file, kind))
                }
            }
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            value = DocumentContent.Failed(e.message ?: "Die Datei konnte nicht gelesen werden.")
        } catch (_: Exception) {
            value = DocumentContent.Failed("Die Datei konnte nicht gelesen werden.")
        } finally {
            pages?.close()
        }
    }

/**
 * Schriftgröße für Texte und Zoom für PDFs. Die Schriftgröße merkt sich die App, weil man
 * meist immer gleich weit vom Handy entfernt liest.
 */
@Stable
class ReaderSettings internal constructor(initialTextSize: Float) {
    var textSize by mutableFloatStateOf(initialTextSize)
        private set
    var pdfZoom by mutableFloatStateOf(1f)
        private set

    fun zoomText(factor: Float) {
        textSize = (textSize * factor).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    }

    fun zoomPdf(factor: Float) {
        pdfZoom = (pdfZoom * factor).coerceIn(1f, MAX_PDF_ZOOM)
    }

    /** Ein Schritt größer oder kleiner, passend zur Art des Dokuments. */
    fun step(kind: DocumentKind, larger: Boolean) {
        val factor = if (larger) STEP else 1 / STEP
        if (kind == DocumentKind.Pdf) zoomPdf(factor) else zoomText(factor)
    }

    fun canStep(kind: DocumentKind, larger: Boolean): Boolean = if (kind == DocumentKind.Pdf) {
        if (larger) pdfZoom < MAX_PDF_ZOOM else pdfZoom > 1f
    } else {
        if (larger) textSize < MAX_TEXT_SIZE else textSize > MIN_TEXT_SIZE
    }

    companion object {
        const val DEFAULT_TEXT_SIZE = 20f
        private const val MIN_TEXT_SIZE = 12f
        private const val MAX_TEXT_SIZE = 44f
        private const val MAX_PDF_ZOOM = 4f
        private const val STEP = 1.15f
        internal const val PREFS = "reader"
        internal const val KEY_TEXT_SIZE = "textSize"
    }
}

@Composable
fun rememberReaderSettings(): ReaderSettings {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ReaderSettings.PREFS, Context.MODE_PRIVATE) }
    val settings = remember { ReaderSettings(prefs.getFloat(ReaderSettings.KEY_TEXT_SIZE, ReaderSettings.DEFAULT_TEXT_SIZE)) }
    LaunchedEffect(settings) {
        snapshotFlow { settings.textSize }.drop(1).collectLatest {
            delay(500) // nicht bei jedem Zwischenschritt einer Zwei-Finger-Geste speichern
            prefs.edit().putFloat(ReaderSettings.KEY_TEXT_SIZE, it).apply()
        }
    }
    return settings
}

/** Knöpfe für größere und kleinere Schrift bzw. Zoom. */
@Composable
fun ReaderSizeButtons(settings: ReaderSettings, kind: DocumentKind) {
    val pdf = kind == DocumentKind.Pdf
    IconButton(onClick = { settings.step(kind, larger = false) }, enabled = settings.canStep(kind, larger = false)) {
        Icon(
            if (pdf) Icons.Filled.ZoomOut else Icons.Filled.TextDecrease,
            contentDescription = if (pdf) "Verkleinern" else "Schrift kleiner",
        )
    }
    IconButton(onClick = { settings.step(kind, larger = true) }, enabled = settings.canStep(kind, larger = true)) {
        Icon(
            if (pdf) Icons.Filled.ZoomIn else Icons.Filled.TextIncrease,
            contentDescription = if (pdf) "Vergrößern" else "Schrift größer",
        )
    }
}

/**
 * Zeigt ein Dokument zum Lesen an: Text mit einstellbarer Schriftgröße, PDF seitenweise.
 * Mit zwei Fingern lässt sich die Schrift bzw. das PDF vergrößern. Braucht eine begrenzte
 * Höhe (z. B. fillMaxSize oder heightIn(max = …)).
 */
@Composable
fun DocumentView(
    content: DocumentContent,
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    when (content) {
        DocumentContent.Loading -> Box(modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is DocumentContent.Failed -> Box(modifier.fillMaxWidth().padding(contentPadding)) {
            Text(content.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        is DocumentContent.Text -> TextDocumentView(content.document, settings, modifier, contentPadding)
        is DocumentContent.Pdf -> PdfView(content.pages, settings, modifier, contentPadding)
    }
}

@Composable
private fun TextDocumentView(
    document: TextDocument,
    settings: ReaderSettings,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    if (document.isBlank()) {
        Box(modifier.fillMaxWidth().padding(contentPadding)) {
            Text(
                "In dieser Datei steht kein Text. Enthält sie nur Bilder, öffne sie in einer anderen App.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val size = settings.textSize
    LazyColumn(
        modifier = modifier.fillMaxWidth().pinchToZoom(settings::zoomText),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy((size * 0.7f).dp),
    ) {
        items(document.blocks) { block -> BlockText(block, size) }
        if (document.truncated) {
            item {
                Text(
                    "… Der Text ist sehr lang; hier wird nur der Anfang angezeigt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BlockText(block: TextBlock, size: Float) {
    val base = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.45f).sp)
    val style = when (block.style) {
        BlockStyle.Heading1 -> base.copy(fontSize = (size * 1.45f).sp, lineHeight = (size * 1.8f).sp, fontWeight = FontWeight.Bold)
        BlockStyle.Heading2 -> base.copy(fontSize = (size * 1.25f).sp, lineHeight = (size * 1.6f).sp, fontWeight = FontWeight.Bold)
        BlockStyle.Heading3 -> base.copy(fontSize = (size * 1.1f).sp, fontWeight = FontWeight.Bold)
        else -> base
    }
    val text = remember(block) { block.annotated() }
    val indent = (block.level * 20).dp
    when (block.style) {
        BlockStyle.ListItem -> Row(Modifier.padding(start = indent)) {
            Text(block.marker.orEmpty(), style = style, modifier = Modifier.widthIn(min = (size * 1.6f).dp))
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
        BlockStyle.Quote -> Row(Modifier.padding(start = indent).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(12.dp))
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
        else -> Text(text, style = style, modifier = Modifier.padding(start = indent))
    }
}

private fun TextBlock.annotated(): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        if (!span.bold && !span.italic) {
            append(span.text)
        } else {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
            withStyle(style) { append(span.text) }
        }
    }
}

@Composable
private fun PdfView(pages: PdfPages, settings: ReaderSettings, modifier: Modifier, contentPadding: PaddingValues) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pinchToZoom(settings::zoomPdf),
    ) {
        val zoom = settings.pdfZoom
        val width = maxWidth * zoom
        val density = LocalDensity.current
        // In Stufen rendern, damit nicht jeder Zwischenschritt beim Zoomen neu gerendert wird.
        val viewportPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val step = (viewportPx / 2).coerceAtLeast(64)
        val renderPx = (ceil(viewportPx * zoom / step).toInt() * step).coerceAtLeast(step)
        LazyColumn(
            modifier = Modifier
                .horizontalScroll(rememberScrollState(), enabled = zoom > 1f)
                .width(width),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pages.pageCount) { index ->
                val size = pages.pageSizes[index]
                PdfPage(
                    pages = pages,
                    index = index,
                    renderPx = renderPx,
                    modifier = Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height),
                )
            }
        }
    }
}

@Composable
private fun PdfPage(pages: PdfPages, index: Int, renderPx: Int, modifier: Modifier) {
    var bitmap by remember(pages, index) { mutableStateOf<ImageBitmap?>(null) }
    // Das bisherige Bild bleibt stehen, bis das schärfere fertig ist.
    LaunchedEffect(pages, index, renderPx) {
        val rendered = withContext(Dispatchers.IO) { runCatching { pages.render(index, renderPx) }.getOrNull() }
        if (rendered != null) bitmap = rendered.asImageBitmap()
    }
    Box(modifier.background(Color.White)) {
        bitmap?.let {
            Image(it, contentDescription = "Seite ${index + 1} von ${pages.pageCount}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Zoomen mit zwei Fingern. Gesten mit einem Finger bleiben unangetastet, damit das Scrollen
 * weiter funktioniert.
 */
@Composable
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier {
    val currentOnZoom by rememberUpdatedState(onZoom)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) currentOnZoom(zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
