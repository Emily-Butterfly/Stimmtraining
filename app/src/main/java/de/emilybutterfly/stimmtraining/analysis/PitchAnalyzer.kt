package de.emilybutterfly.stimmtraining.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Tonhöhen-Analyse ganz auf dem Gerät: nur der Grundton (F0) der Stimme, ausgegeben als
 * häufigste, tiefste und höchste Note. Ablauf: Dekodieren (gestreamt) → Mono →
 * Abtastratenwandlung auf [ANALYSIS_RATE] → Bandpass 45 Hz … 2 kHz → YIN alle 10 ms → Statistik.
 */
object PitchAnalyzer {
    /** Analyse-Abtastrate: reicht für Grundtöne bis 1000 Hz samt den wichtigsten Obertönen. */
    internal const val ANALYSIS_RATE = 8000

    /** Hochpass knapp unter der tiefsten gesuchten Stimme (60 Hz): gegen Gleichanteil und Rumpeln. */
    private const val HIGH_PASS_HZ = 45.0

    /**
     * Tiefpass: Grundtöne bis 1000 Hz bleiben samt 2. Teilton erhalten, hohe Obertöne fallen weg.
     * So ist die Differenzfunktion bei 8 kHz fein genug abgetastet, dass hohe Stimmen nicht an
     * ganzzahligen Verschiebungen „vorbeirutschen“ und als Oktave tiefer erkannt werden.
     */
    private const val LOW_PASS_HZ = 2000.0

    private const val CHUNK = 4096

    /**
     * Analysiert die Audiodatei vollständig auf dem Gerät (MediaExtractor + MediaCodec, gestreamt).
     * Läuft auf Dispatchers.Default, prüft regelmäßig auf Abbruch (coroutine cancellation), meldet
     * Fortschritt 0..1 über [onProgress]. Wirft eine IOException mit deutscher Meldung, wenn die
     * Datei nicht gelesen/dekodiert werden kann.
     *
     * [onProgress] wird im Hintergrund-Thread der Analyse aufgerufen (in Schritten von etwa 1 %,
     * zuletzt mit 1); ohne bekannte Spurlänge kommt nur 0 und am Ende 1.
     */
    suspend fun analyzeFile(file: File, onProgress: (Float) -> Unit = {}): PitchResult = withContext(Dispatchers.Default) {
        val pipeline = Pipeline()
        AudioDecoder.decode(file, onProgress) { samples, count, sampleRate -> pipeline.feed(samples, 0, count, sampleRate) }
        val result = pipeline.finish()
        onProgress(1f)
        result
    }

    /** Analysiert Mono-PCM-Samples (Wertebereich etwa -1..1); für Tests und intern. */
    fun analyze(samples: FloatArray, sampleRate: Int): PitchResult {
        require(sampleRate > 0) { "Ungültige Abtastrate: $sampleRate" }
        val pipeline = Pipeline()
        var offset = 0
        while (offset < samples.size) {
            val n = min(CHUNK, samples.size - offset)
            pipeline.feed(samples, offset, n, sampleRate)
            offset += n
        }
        return pipeline.finish()
    }

    /** Verarbeitungskette für Mono-PCM in beliebig großen Blöcken. */
    internal class Pipeline {
        val tracker = PitchTracker(ANALYSIS_RATE)
        private val filters = listOf(Biquad.highPass(ANALYSIS_RATE, HIGH_PASS_HZ)) + Biquad.lowPass4(ANALYSIS_RATE, LOW_PASS_HZ)
        private var resampler: Resampler? = null

        fun feed(samples: FloatArray, offset: Int, count: Int, sampleRate: Int) {
            require(sampleRate > 0) { "Ungültige Abtastrate: $sampleRate" }
            var r = resampler
            if (r == null || r.inputRate != sampleRate) {
                // Neue Rate (Formatwechsel des Decoders): Filter neu aufsetzen
                r = Resampler(sampleRate, ANALYSIS_RATE)
                resampler = r
            }
            val n = r.process(samples, offset, count)
            if (n == 0) return
            for (filter in filters) filter.process(r.output, n)
            tracker.feed(r.output, n)
        }

        fun finish(): PitchResult {
            tracker.finish()
            return PitchStatistics.compute(tracker.f0.data, tracker.energyDb.data, tracker.frameCount, tracker.hopMs)
        }
    }
}
