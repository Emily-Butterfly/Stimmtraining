package de.emilybutterfly.stimmtraining.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.emilybutterfly.stimmtraining.analysis.Notes
import de.emilybutterfly.stimmtraining.analysis.PitchResult
import kotlin.math.roundToInt

/**
 * Tiefste, häufigste und höchste Note einer Aufnahme – oder der Knopf, sie zu ermitteln.
 * [result] null: noch nicht analysiert. [progress] gesetzt: Analyse läuft.
 */
@Composable
fun PitchCard(result: PitchResult?, progress: Float?, onAnalyze: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Tonhöhe", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (result != null && progress == null) {
                    TextButton(onClick = onAnalyze) { Text("Neu analysieren") }
                }
            }
            val most = result?.mostFrequent
            val lowest = result?.lowest
            val highest = result?.highest
            when {
                progress != null -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Die Tonhöhe wird analysiert …", style = MaterialTheme.typography.bodySmall)
                }
                result == null -> {
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
                        "Ausgewertet: ${formatSpokenTime(result.voicedMs)} Stimme. Kurze Ausreißer zählen nicht.",
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
