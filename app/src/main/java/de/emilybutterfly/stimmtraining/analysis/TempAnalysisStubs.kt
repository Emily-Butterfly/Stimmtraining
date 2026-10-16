package de.emilybutterfly.stimmtraining.analysis

import java.io.File

// VORLÄUFIG: wird durch die echte Analyse ersetzt.
data class PitchResult(val mostFrequent: Int?, val lowest: Int?, val highest: Int?, val voicedMs: Long) {
    val hasVoice: Boolean get() = mostFrequent != null
}

object Notes {
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)
    fun frequency(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
    fun fromFrequency(hz: Double): Int = Math.round(69 + 12 * Math.log(hz / 440.0) / Math.log(2.0)).toInt()
}

object PitchAnalyzer {
    suspend fun analyzeFile(file: File, onProgress: (Float) -> Unit = {}): PitchResult = PitchResult(57, 52, 62, 1000)
    fun analyze(samples: FloatArray, sampleRate: Int): PitchResult = PitchResult(null, null, null, 0)
}
