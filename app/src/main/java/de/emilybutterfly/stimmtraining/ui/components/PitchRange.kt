package de.emilybutterfly.stimmtraining.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import de.emilybutterfly.stimmtraining.analysis.Notes
import kotlin.math.roundToInt

/** „E3 · A3 · D4“: tiefste, häufigste (fett) und höchste Note. */
fun noteRange(lowest: Int, mostFrequent: Int, highest: Int): AnnotatedString = buildAnnotatedString {
    append(Notes.name(lowest))
    append(" · ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(Notes.name(mostFrequent)) }
    append(" · ")
    append(Notes.name(highest))
}

/** Für Screenreader, die „·“ und Fettdruck nicht vorlesen. */
fun noteRangeDescription(lowest: Int, mostFrequent: Int, highest: Int): String =
    "Tiefste Note ${Notes.name(lowest)}, häufigste ${Notes.name(mostFrequent)}, höchste ${Notes.name(highest)}"

/** „A3 · 220 Hz“ */
fun noteWithHz(midi: Int): String = "${Notes.name(midi)} · ${Notes.frequency(midi).roundToInt()} Hz"
