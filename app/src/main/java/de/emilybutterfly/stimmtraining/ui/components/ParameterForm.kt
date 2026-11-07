package de.emilybutterfly.stimmtraining.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.data.takeWhole

val EXERCISE_SUGGESTIONS = listOf(
    "Text lesen",
    "Twang",
    "Spontan sprechen",
    "Resonanz",
    "Tonhöhe",
    "Sirenen / Glides",
    "Intonation",
    "Summen",
    "Telefonat",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParameterForm(
    params: RecordingParams,
    onChange: (RecordingParams) -> Unit,
    modifier: Modifier = Modifier,
    /** Während des Speicherns gesperrt: Was jetzt noch getippt würde, ginge verloren. */
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = params.title,
            onValueChange = { onChange(params.copy(title = it.takeWhole(Recording.MAX_TITLE))) },
            label = { Text("Titel / Text (optional)") },
            placeholder = { Text("z. B. Der Nordwind und die Sonne") },
            supportingText = lengthHint(params.title, Recording.MAX_TITLE),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Die ganze Zeile ist der Schalter: TalkBack liest „Vorher aufgewärmt, Ein, Schalter“.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = params.warmedUp,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onChange(params.copy(warmedUp = it)) },
                    ),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Vorher aufgewärmt", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (params.warmedUp) "Ja" else "Nein",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = params.warmedUp, onCheckedChange = null, enabled = enabled)
                }
                if (params.warmedUp) {
                    val minutes = params.warmUpMinutes.toIntOrNull()
                    val tooLong = minutes != null && minutes > RecordingParams.MAX_WARM_UP_MINUTES
                    OutlinedTextField(
                        value = params.warmUpMinutes,
                        onValueChange = { value ->
                            onChange(params.copy(warmUpMinutes = value.filter(Char::isDigit).take(3)))
                        },
                        label = { Text("Aufwärmdauer in Minuten (optional)") },
                        isError = tooLong,
                        supportingText = if (tooLong) {
                            { Text("Höchstens ${RecordingParams.MAX_WARM_UP_MINUTES} Minuten – sonst wird keine Dauer gespeichert.") }
                        } else {
                            null
                        },
                        enabled = enabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        }

        OutlinedTextField(
            value = params.exercise,
            onValueChange = { onChange(params.copy(exercise = it.takeWhole(Recording.MAX_EXERCISE))) },
            label = { Text("Übung") },
            supportingText = lengthHint(params.exercise, Recording.MAX_EXERCISE),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXERCISE_SUGGESTIONS.forEach { suggestion ->
                FilterChip(
                    selected = params.exercise == suggestion,
                    enabled = enabled,
                    onClick = {
                        onChange(params.copy(exercise = if (params.exercise == suggestion) "" else suggestion))
                    },
                    label = { Text(suggestion) },
                )
            }
        }

        RatingInput(
            title = "Stimmliche Tagesform",
            value = params.voiceCondition,
            labels = VOICE_CONDITION_LABELS,
            onChange = { onChange(params.copy(voiceCondition = it)) },
            enabled = enabled,
        )
        RatingInput(
            title = "Zufriedenheit mit der Aufnahme",
            value = params.selfRating,
            labels = SELF_RATING_LABELS,
            onChange = { onChange(params.copy(selfRating = it)) },
            enabled = enabled,
        )

        OutlinedTextField(
            value = params.notes,
            onValueChange = { onChange(params.copy(notes = it.takeWhole(Recording.MAX_NOTES))) },
            label = { Text("Notizen") },
            placeholder = { Text("Wie hat es sich angefühlt? Worauf hast du geachtet?") },
            supportingText = lengthHint(params.notes, Recording.MAX_NOTES),
            enabled = enabled,
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Zeigt erst kurz vor der Grenze, wie viele Zeichen noch gehen – sonst wird still gekürzt. */
private fun lengthHint(text: String, max: Int): (@Composable () -> Unit)? =
    if (text.length < max * 9 / 10) null else { { Text("${text.length} von $max Zeichen") } }

/** Fünf Sterne; ein erneuter Tipp auf den gewählten Stern setzt die Bewertung zurück. */
@Composable
private fun RatingInput(title: String, value: Int?, labels: List<String>, onChange: (Int?) -> Unit, enabled: Boolean = true) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
            for (i in 1..5) {
                IconButton(
                    onClick = { onChange(if (value == i) null else i) },
                    enabled = enabled,
                    // „Stimmliche Tagesform: 3 von 5, ausgewählt“ – bei zwei Sternreihen ist sonst unklar, welche gemeint ist.
                    modifier = Modifier.semantics { selected = value == i },
                ) {
                    Icon(
                        imageVector = if (value != null && i <= value) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "$title: $i von 5",
                        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
            Text(
                text = value?.let { labels[it - 1] } ?: "nicht angegeben",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
fun RatingDisplay(value: Int, modifier: Modifier = Modifier, iconSize: Dp = 18.dp) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "$value von 5" }) {
        for (i in 1..5) {
            Icon(
                imageVector = if (i <= value) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
