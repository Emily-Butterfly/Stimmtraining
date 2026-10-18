package de.emilybutterfly.stimmtraining.ui.pitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.analysis.Notes
import de.emilybutterfly.stimmtraining.ui.gentleMode
import de.emilybutterfly.stimmtraining.ui.components.formatDateTime
import de.emilybutterfly.stimmtraining.ui.components.noteRange
import de.emilybutterfly.stimmtraining.ui.components.noteRangeDescription
import de.emilybutterfly.stimmtraining.ui.components.noteWithHz
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Wie sich die Tonhöhe über die analysierten Aufnahmen entwickelt: Diagramm und Tabelle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitchHistoryScreen(
    onClose: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    viewModel: PitchHistoryViewModel = viewModel(factory = PitchHistoryViewModel.Factory),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tonhöhen-Verlauf") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        val loaded = history
        when {
            gentleMode() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Die Tonhöhe ist ausgeblendet (sanfter Modus). Einblenden kannst du sie in den Einstellungen.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            loaded == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !loaded.anyAnalyzed -> EmptyHint(Modifier.fillMaxSize().padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    Text(
                        "Jeder Punkt ist die häufigste Note einer analysierten Aufnahme; der Strich reicht von " +
                            "ihrer tiefsten bis zu ihrer höchsten Note. Notennamen wie in Stimmgeräte-Apps: " +
                            "C4 ist das mittlere C, B das deutsche H.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item(key = "filters") { Filters(loaded, viewModel::setPeriod, viewModel::setExercise) }
                if (loaded.points.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "Im gewählten Zeitraum gibt es keine analysierten Aufnahmen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item(key = "chart") { ChartCard(loaded.points, onOpenRecording) }
                    item(key = "table-title") {
                        Text("Alle Werte", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    // Die Tabelle zeigt dieselben Werte wie das Diagramm – auch ohne Antippen lesbar.
                    items(loaded.points.asReversed(), key = { it.id }) { point ->
                        PointRow(point, onClick = { onOpenRecording(point.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(history: PitchHistory, onPeriod: (PitchPeriod) -> Unit, onExercise: (String?) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PitchPeriod.entries.forEach { period ->
            FilterChip(selected = history.period == period, onClick = { onPeriod(period) }, label = { Text(period.label) })
        }
        if (history.exercises.size >= 2) {
            VerticalDivider(Modifier.height(32.dp))
            FilterChip(selected = history.exercise == null, onClick = { onExercise(null) }, label = { Text("Alle Übungen") })
            history.exercises.forEach { exercise ->
                FilterChip(
                    selected = history.exercise == exercise,
                    onClick = { onExercise(exercise) },
                    label = {
                        Text(
                            exercise.ifBlank { "Ohne Übung" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ChartCard(points: List<PitchPoint>, onOpenRecording: (Long) -> Unit) {
    // Ausgewählt ist anfangs die neueste Aufnahme; nach einem Filterwechsel ebenso.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedIndex = points.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: points.lastIndex
    val selected = points[selectedIndex]

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Häufigste Note je Aufnahme", style = MaterialTheme.typography.titleSmall)
            PitchChart(points, selectedIndex, onSelect = { selectedId = points[it].id }, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        formatDateTime(selected.recordedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(selected.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        noteRange(selected.lowest, selected.mostFrequent, selected.highest),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            contentDescription = noteRangeDescription(selected.lowest, selected.mostFrequent, selected.highest)
                        },
                    )
                    Text(
                        "Häufigste Note: ${noteWithHz(selected.mostFrequent)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenRecording(selected.id) }) { Text("Öffnen") }
            }
        }
    }
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMANY)
private val SHORT_DATE_YEAR = DateTimeFormatter.ofPattern("d.M.yy", Locale.GERMANY)

/**
 * Häufigste Note je Aufnahme über die Zeit (Punkte, verbunden), dahinter je ein Strich von der
 * tiefsten bis zur höchsten Note. Eine Reihe in einem Farbton: Punkt und Linie in der
 * Hauptfarbe, der Strich eine hellere Stufe davon (gegen beide Hintergründe geprüft).
 * Antippen wählt die zeitlich nächste Aufnahme.
 */
@Composable
private fun PitchChart(points: List<PitchPoint>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val markColor = colors.primary
    val rangeColor = if (dark) Color(0xFF8A6874) else Color(0xFFD4A7B9)
    val ringColor = colors.surface
    val gridColor = colors.outlineVariant
    val crosshairColor = colors.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant, fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val scale = remember(points) { NoteScale.of(points) }
    val tickLabels = remember(scale, labelStyle) { scale.ticks.map { measurer.measure(Notes.name(it), labelStyle) } }
    val dateLabels = remember(points, labelStyle) { dateLabels(points).map { it to measurer.measure(it.second, labelStyle) } }
    val gutter = with(density) { (tickLabels.maxOf { it.size.width } + 8.dp.toPx()) }
    val edge = with(density) { 10.dp.toPx() }
    val currentOnSelect by rememberUpdatedState(onSelect)

    val summary = remember(points) {
        val first = points.first().recordedAt.zoned()
        val last = points.last().recordedAt.zoned()
        val format = if (first.year == last.year) SHORT_DATE else SHORT_DATE_YEAR
        val count = if (points.size == 1) "1 Aufnahme" else "${points.size} Aufnahmen"
        val period = if (first.toLocalDate() == last.toLocalDate()) {
            "am ${format.format(first)}"
        } else {
            "vom ${format.format(first)} bis ${format.format(last)}"
        }
        "Diagramm mit $count $period. Zuletzt häufigste Note ${Notes.name(points.last().mostFrequent)}. " +
            "Insgesamt von ${Notes.name(points.minOf { it.lowest })} bis ${Notes.name(points.maxOf { it.highest })}."
    }

    Canvas(
        modifier
            .height(240.dp)
            .semantics { contentDescription = summary }
            .pointerInput(points, gutter) {
                detectTapGestures { tap ->
                    val xs = xPositions(points, gutter + edge, size.width - edge)
                    val nearest = xs.indices.minBy { abs(xs[it] - tap.x) }
                    currentOnSelect(nearest)
                }
            },
    ) {
        val axisBand = 24.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - axisBand
        fun y(midi: Int) = bottom - (midi - scale.low).toFloat() / (scale.high - scale.low) * (bottom - top)

        // Raster und Notennamen: Haarlinien, zurückhaltend.
        scale.ticks.forEachIndexed { i, midi ->
            val yy = y(midi)
            drawLine(gridColor, Offset(gutter, yy), Offset(size.width, yy), strokeWidth = 1f)
            val label = tickLabels[i]
            drawText(label, topLeft = Offset(gutter - 6.dp.toPx() - label.size.width, yy - label.size.height / 2f))
        }

        val xs = xPositions(points, gutter + edge, size.width - edge)
        drawDateLabels(dateLabels, xs, gutter, bottom + 6.dp.toPx())

        // Fadenkreuz an der ausgewählten Aufnahme.
        drawLine(crosshairColor, Offset(xs[selected], top), Offset(xs[selected], bottom), strokeWidth = 1f)

        val barWidth = 4.dp.toPx()
        points.forEachIndexed { i, point ->
            drawLine(rangeColor, Offset(xs[i], y(point.highest)), Offset(xs[i], y(point.lowest)), barWidth, StrokeCap.Round)
        }
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, point ->
                if (i == 0) path.moveTo(xs[i], y(point.mostFrequent)) else path.lineTo(xs[i], y(point.mostFrequent))
            }
            drawPath(path, markColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEachIndexed { i, point ->
            val center = Offset(xs[i], y(point.mostFrequent))
            val radius = (if (i == selected) 6.dp else 4.dp).toPx()
            drawCircle(ringColor, radius + 2.dp.toPx(), center) // Ring in Hintergrundfarbe
            drawCircle(markColor, radius, center)
        }
    }
}

/** Wertebereich der Notenachse mit sauberen Teilstrichen (Halbtöne, auf C ausgerichtet). */
private class NoteScale(val low: Int, val high: Int, val ticks: List<Int>) {
    companion object {
        fun of(points: List<PitchPoint>): NoteScale {
            var low = points.minOf { it.lowest } - 1
            var high = points.maxOf { it.highest } + 1
            if (high - low < 6) {
                val extra = 6 - (high - low)
                low -= extra / 2
                high += extra - extra / 2
            }
            // Sehr große Bereiche (nur aus beschädigten Sicherungen möglich) bekommen Oktavschritte.
            val step = listOf(1, 2, 3, 4, 6, 12).firstOrNull { (high - low) / it <= 6 } ?: (12 * ((high - low) / 72 + 1))
            return NoteScale(low, high, (low..high).filter { Math.floorMod(it, step) == 0 })
        }
    }
}

/** X-Position jeder Aufnahme proportional zu ihrem Zeitpunkt. */
private fun xPositions(points: List<PitchPoint>, left: Float, right: Float): FloatArray {
    val start = points.first().recordedAt
    val span = points.last().recordedAt - start
    return FloatArray(points.size) { i ->
        if (span <= 0L) (left + right) / 2f else left + (points[i].recordedAt - start).toFloat() / span * (right - left)
    }
}

/** Erste, (bei genug Abstand) mittlere und letzte Aufnahme beschriften. */
private fun dateLabels(points: List<PitchPoint>): List<Pair<Int, String>> {
    val first = points.first().recordedAt.zoned()
    val last = points.last().recordedAt.zoned()
    val format = if (first.year == last.year) SHORT_DATE else SHORT_DATE_YEAR
    if (first.toLocalDate() == last.toLocalDate()) return listOf(-1 to format.format(first))
    val labels = mutableListOf(0 to format.format(first), points.lastIndex to format.format(last))
    if (points.size >= 3) {
        val middle = points.size / 2
        val date = points[middle].recordedAt.zoned()
        if (date.toLocalDate() != first.toLocalDate() && date.toLocalDate() != last.toLocalDate()) {
            labels.add(1, middle to format.format(date))
        }
    }
    return labels
}

/** Index -1 steht für „alle am selben Tag“: dann mittig. Beschriftungen überlappen nie. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDateLabels(
    labels: List<Pair<Pair<Int, String>, TextLayoutResult>>,
    xs: FloatArray,
    left: Float,
    top: Float,
) {
    val gap = 8.dp.toPx()
    val placed = labels.map { (label, layout) ->
        val index = label.first
        val width = layout.size.width.toFloat()
        val anchor = if (index < 0) (xs.first() + xs.last()) / 2f else xs[index]
        // Mittig unter dem Punkt, aber nie über den Rand hinaus.
        val x = (anchor - width / 2f).coerceIn(left, (size.width - width).coerceAtLeast(left))
        Triple(x, x + width, layout)
    }
    // Erstes und letztes Datum haben Vorrang; das mittlere nur, wenn es keines davon berührt.
    val drawn = mutableListOf<Pair<Float, Float>>()
    val order = if (placed.size == 3) listOf(placed[0], placed[2], placed[1]) else placed
    for ((start, end, layout) in order) {
        if (drawn.any { (s, e) -> start < e + gap && end + gap > s }) continue
        drawText(layout, topLeft = Offset(start, top))
        drawn += start to end
    }
}

private fun Long.zoned() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

@Composable
private fun PointRow(point: PitchPoint, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Aufnahme öffnen", onClick = onClick)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(point.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatDateTime(point.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                noteRange(point.lowest, point.mostFrequent, point.highest),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 12.dp).semantics {
                    contentDescription = noteRangeDescription(point.lowest, point.mostFrequent, point.highest)
                },
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Text("Noch keine analysierten Aufnahmen", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Öffne eine Aufnahme und tippe auf „Tonhöhe analysieren“. Dann erscheint sie hier im Verlauf.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
