package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Notennamen wie in den Techniktexten: international mit Kreuzen, mittleres C = C4,
 * gleichstufig gestimmt mit A4 = 440 Hz (MIDI 69).
 */
object Notes {
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private const val A4 = 69
    private const val A4_HZ = 440.0

    /** "A3", "F#3", "C4" … */
    fun name(midi: Int): String = NAMES[midi.mod(12)] + (midi.floorDiv(12) - 1)

    /** Frequenz der Note in Hz (gleichstufig, A4 = 440 Hz). */
    fun frequency(midi: Int): Double = A4_HZ * 2.0.pow((midi - A4) / 12.0)

    /** Nächstgelegene Note zu einer Frequenz. */
    fun fromFrequency(hz: Double): Int {
        require(hz > 0.0 && hz.isFinite()) { "Ungültige Frequenz: $hz" }
        return semitones(hz).roundToInt()
    }

    /** Tonhöhe als gebrochene MIDI-Nummer (69,5 liegt genau zwischen A4 und A#4). */
    internal fun semitones(hz: Double): Double = A4 + 12 * log2(hz / A4_HZ)
}
