package de.emilybutterfly.stimmtraining.analysis

/** Ergebnis der Tonhöhen-Analyse; Noten als MIDI-Nummern (A4 = 69 = 440 Hz). Ohne genug Stimme sind die Noten null. */
data class PitchResult(
    val mostFrequent: Int?,
    val lowest: Int?,
    val highest: Int?,
    /** Dauer der Abschnitte mit erkannter Stimme, die ausgewertet wurden. */
    val voicedMs: Long,
) {
    val hasVoice: Boolean get() = mostFrequent != null
}
