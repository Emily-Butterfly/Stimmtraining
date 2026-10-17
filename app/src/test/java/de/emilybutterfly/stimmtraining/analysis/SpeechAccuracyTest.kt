package de.emilybutterfly.stimmtraining.analysis

import de.emilybutterfly.stimmtraining.analysis.Speech.Fricative
import de.emilybutterfly.stimmtraining.analysis.Speech.Pause
import de.emilybutterfly.stimmtraining.analysis.Speech.Voice
import de.emilybutterfly.stimmtraining.analysis.Speech.Whisper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Genauigkeit auf sprachähnlichen Signalen ([Speech]): Glottispulse durch Vokal-Formanten (oft ist
 * der 2. oder 3. Teilton stärker als der Grundton), Satzmelodien, schwacher oder fehlender
 * Grundton, Hintergrundrauschen, Jitter und Shimmer, Knarrstimme am Phrasenende, Pausen,
 * Flüstern, sehr leise und übersteuerte Aufnahmen. Die Erwartung stammt immer aus der wahren
 * Grundfrequenz des Signals; mehrere Noten sind nur erlaubt, wo die Wahrheit auf einer
 * Notengrenze oder knapp an der Mindestdauer liegt ([Speech.expected]).
 */
class SpeechAccuracyTest {
    private val rate = 44_100

    private fun mismatch(label: String, signal: Speech.Signal, expected: Speech.Expected = Speech.expected(signal.truth)): String? =
        Speech.mismatch(PitchAnalyzer.analyze(signal.samples, signal.rate), expected)?.let { "$label: $it" }

    private fun assertNoMismatch(failures: List<String?>) {
        val list = failures.filterNotNull()
        if (list.isNotEmpty()) fail("${list.size} Fälle falsch:\n" + list.joinToString("\n"))
    }

    private fun heldVowel(midi: Int, vowel: Speech.Vowel, seconds: Double = 1.0) =
        listOf(Pause(0.15), Voice(seconds, vowel, pitch = Speech.held(midi.toDouble(), 0.1)), Pause(0.15))

    /** Vierpoliger Hochpass (Butterworth), etwa wie ein Telefonkanal unten. */
    private fun highPass4(signal: Speech.Signal, hz: Double): Speech.Signal {
        val s = signal.samples.copyOf()
        Biquad.highPass(signal.rate, hz, 0.5412).process(s, s.size)
        Biquad.highPass(signal.rate, hz, 1.3066).process(s, s.size)
        return Speech.Signal(s, signal.rate, signal.truth)
    }

    @Test
    fun formantVowelsKeepTheirOctave() {
        // Männerstimme (Formanten nach Peterson & Barney) und Frauen- bzw. trainierte Stimme (Formanten 17 % höher).
        // Typische Oktavfallen: F1 nahe 2·F0 (/i/, /u/ tief, /o/ um A#3) oder 3·F0 (/a/ um G3).
        val male = 40..57
        val female = 55..73
        val qualities = mapOf("modal" to Speech.MODAL, "rau" to Speech.ROUGH, "behaucht" to Speech.BREATHY)
        val failures = mutableListOf<String?>()
        var seed = 1
        for ((qn, quality) in qualities) {
            for (midi in male step 2) for (vowel in Speech.VOWELS) {
                failures += mismatch("${Notes.name(midi)} /$vowel/ Mann $qn", Speech.synth(heldVowel(midi, vowel), quality = quality, seed = seed++))
            }
            for (midi in female step 2) for (vowel in Speech.VOWELS) {
                val signal = Speech.synth(heldVowel(midi, vowel.scaled(1.17)), quality = quality, seed = seed++)
                failures += mismatch("${Notes.name(midi)} /$vowel/ Frau $qn", signal)
            }
        }
        assertNoMismatch(failures)
    }

    @Test
    fun highVoiceOnOpenVowels() {
        // Kopfstimme bis G5 auf /a/, /e/, /o/ (F1 nahe F0 oder darüber)
        val failures = mutableListOf<String?>()
        for (midi in 74..79) for (vowel in listOf(Speech.A, Speech.E, Speech.O)) for (quality in listOf(Speech.MODAL, Speech.BREATHY)) {
            failures += mismatch("${Notes.name(midi)} /$vowel/", Speech.synth(heldVowel(midi, vowel.scaled(1.17)), quality = quality, seed = midi))
        }
        assertNoMismatch(failures)
    }

    @Test
    fun strongSecondHarmonicInBackgroundNoise() {
        // F1 genau auf 2·F0: der 2. Teilton überwiegt deutlich; dazu Rauschen mit 20 dB Abstand
        val cases = listOf(48 to Speech.I, 52 to Speech.U, 58 to Speech.O, 61 to Speech.O.scaled(1.17), 52 to Speech.U.scaled(1.17), 55 to Speech.A)
        val failures = mutableListOf<String?>()
        for ((midi, vowel) in cases) for (pink in listOf(true, false)) for (quality in listOf(Speech.MODAL, Speech.ROUGH)) {
            val clean = Speech.synth(heldVowel(midi, vowel, 1.5), quality = quality, seed = midi)
            failures += mismatch("${Notes.name(midi)} /$vowel/ ${if (pink) "rosa" else "weiß"}", Speech.withNoise(clean, 20.0, pink = pink, seed = midi))
        }
        assertNoMismatch(failures)
    }

    @Test
    fun missingFundamental() {
        // Pulsfolge und Stimme durch einen steilen Hochpass bei 300 Hz: der Grundton fehlt praktisch ganz
        val failures = mutableListOf<String?>()
        for (midi in listOf(43, 45, 48, 50, 53, 55)) {
            val hz = Notes.frequency(midi)
            val pulses = Signals.tone(1.2, rate, hz, harmonics = DoubleArray(40) { 1.0 })
            val truth = FloatArray(120) { if (it < 115) hz.toFloat() else 0f }
            failures += mismatch("Pulse ${Notes.name(midi)}", highPass4(Speech.Signal(pulses, rate, truth), 300.0))
            for (vowel in listOf(Speech.A, Speech.E, Speech.O)) {
                val voice = Speech.synth(heldVowel(midi, vowel), seed = midi)
                failures += mismatch("${Notes.name(midi)} /$vowel/ Hochpass", highPass4(voice, 300.0))
            }
        }
        assertNoMismatch(failures)
    }

    private val speakers = listOf(
        Triple("Mann", 47.0, 1.0), // um B2
        Triple("Frau", 57.0, 1.17), // um A3
        Triple("trainiert", 54.0, 1.12), // um F#3, Resonanz schon heller
    )

    @Test
    fun sentenceIntonation() {
        // Aussagesätze (Akzent, dann Abfall) und Fragen (Anstieg am Ende), gesprochen mit Anlauten und kurzen Pausen
        val failures = mutableListOf<String?>()
        for ((name, base, scale) in speakers) for (seed in 1..6) {
            val syllables = if (seed % 3 == 0) Speech.question(base, 7, seed = seed) else Speech.statement(base, 8, accent = seed % 4, seed = seed)
            val signal = Speech.synth(listOf(Pause(0.2)) + Speech.phrase(syllables, formantScale = scale) + Pause(0.3), seed = seed)
            failures += mismatch("$name Satz $seed", signal)
        }
        assertNoMismatch(failures)
    }

    @Test
    fun sentencesInBackgroundNoise() {
        val failures = mutableListOf<String?>()
        for ((name, base, scale) in speakers) for (seed in 1..4) {
            val segments = listOf(Pause(0.3)) + Speech.phrase(Speech.statement(base, 8, accent = 2, seed = seed), formantScale = scale) + Pause(0.3)
            val clean = Speech.synth(segments, seed = seed)
            failures += mismatch("$name rosa 20 dB $seed", Speech.withNoise(clean, 20.0, pink = true, seed = seed))
            failures += mismatch("$name weiß 20 dB $seed", Speech.withNoise(clean, 20.0, pink = false, seed = seed))
        }
        assertNoMismatch(failures)
    }

    @Test
    fun jitterAndShimmer() {
        // Rau bis sehr rau: Periodenschwankung 1–2 %, Amplitudenschwankung 8–12 %
        val qualities = listOf(Speech.ROUGH, Speech.Quality(jitter = 0.02, shimmer = 0.12, breath = 0.08))
        val failures = mutableListOf<String?>()
        for ((name, base, scale) in speakers) for ((i, quality) in qualities.withIndex()) for (seed in 1..3) {
            val segments = listOf(Pause(0.2)) + Speech.phrase(Speech.statement(base, 8, accent = 1, seed = seed), formantScale = scale) + Pause(0.2)
            failures += mismatch("$name Jitter ${quality.jitter} $seed", Speech.synth(segments, quality = quality, seed = 10 * i + seed))
        }
        assertNoMismatch(failures)
    }

    @Test
    fun vocalFryAtPhraseEndsDoesNotSetTheLowestNote() {
        // Am Ende jeder Phrase 250 ms Knarrstimme (unregelmäßige Pulse um 35–50 Hz); zählt nicht als Ton
        val failures = mutableListOf<String?>()
        for ((name, base, scale) in speakers) for (seed in 1..3) {
            val segments = mutableListOf<Speech.Segment>(Pause(0.2))
            for (phrase in 0 until 3) {
                segments += Speech.phrase(Speech.statement(base, 6, accent = 1, seed = seed * 10 + phrase), fryEnd = 0.25, formantScale = scale)
                segments += Pause(0.4)
            }
            val signal = Speech.synth(segments, seed = seed)
            failures += mismatch("$name Knarren $seed", signal)
        }
        assertNoMismatch(failures)
    }

    @Test
    fun pausesBetweenWords() {
        // Einzelne Wörter mit Pausen, Reibe- und Verschlusslauten dazwischen; Raumrauschen in den Pausen
        val failures = mutableListOf<String?>()
        for ((name, base, scale) in speakers) {
            val segments = mutableListOf<Speech.Segment>(Pause(0.3))
            val words = listOf(0.0 to 2.0, 1.0 to -1.0, 3.0 to 3.0, -2.0 to -3.0, 0.5 to 0.5)
            for ((i, w) in words.withIndex()) {
                segments += Fricative(0.08)
                segments += Voice(0.22, Speech.VOWELS[i].scaled(scale), pitch = Speech.glide(base + w.first, base + w.second, 0.22))
                segments += Pause(0.05)
                segments += Fricative(0.03, -8.0)
                segments += Voice(0.18, Speech.VOWELS[(i + 2) % 5].scaled(scale), -5.0, Speech.glide(base + w.second, base + w.second - 1, 0.18))
                segments += Pause(0.12 + 0.08 * i)
            }
            failures += mismatch("$name Wörter", Speech.synth(segments, roomNoiseDb = -55.0, seed = 3))
        }
        assertNoMismatch(failures)
    }

    @Test
    fun whisperHasNoNotes() {
        // Geflüsterte Sätze: Rauschen durch die Formanten (auch mit schmalen Bandbreiten), Reibelaute, Pausen
        for (damping in listOf(1.0, 2.0)) for (scale in listOf(1.0, 1.17)) {
            val segments = mutableListOf<Speech.Segment>(Pause(0.2))
            for ((i, vowel) in (Speech.VOWELS + Speech.VOWELS).withIndex()) {
                segments += Whisper(0.25, vowel.scaled(scale))
                segments += if (i % 2 == 0) Fricative(0.1, -6.0) else Pause(0.08)
            }
            segments += Pause(0.2)
            val signal = Speech.synth(segments, quality = Speech.MODAL.copy(f1Damping = damping), seed = 4)
            val result = PitchAnalyzer.analyze(signal.samples, rate)
            assertFalse("Dämpfung $damping, Formanten x$scale: ${result.mostFrequent?.let(Notes::name)}", result.hasVoice)
        }
    }

    @Test
    fun breathyWhisperOverSpeechStillFindsTheVoice() {
        // Erst geflüstert, dann gesprochen: nur der gesprochene Teil zählt
        val (_, base, scale) = speakers[1]
        val whisper = listOf(Pause(0.2)) + Speech.VOWELS.map { Whisper(0.3, it.scaled(scale)) }
        val spoken = Speech.phrase(Speech.statement(base, 8, accent = 2, seed = 5), formantScale = scale) + Pause(0.2)
        val signal = Speech.synth(whisper + spoken, quality = Speech.BREATHY, seed = 5)
        assertNoMismatch(listOf(mismatch("geflüstert + gesprochen", signal)))
    }

    private fun sentence(seed: Int = 7): Speech.Signal {
        val (_, base, scale) = speakers[2]
        val segments = listOf(Pause(0.3)) + Speech.phrase(Speech.statement(base, 9, accent = 2, seed = seed), formantScale = scale) +
            Pause(0.4) + Speech.phrase(Speech.question(base, 6, seed = seed + 1), formantScale = scale) + Pause(0.3)
        return Speech.synth(segments, seed = seed)
    }

    @Test
    fun veryQuietRecordingIsStillAnalysed() {
        // Ganze Aufnahme 30 dB leiser (Spitzen bei -36 dBFS, leise Silben um -60 dBFS)
        val signal = sentence()
        val normal = PitchAnalyzer.analyze(signal.samples, rate)
        assertNoMismatch(listOf(mismatch("normal", signal), mismatch("-30 dB", Speech.withGain(signal, -30.0))))
        val quiet = PitchAnalyzer.analyze(Speech.withGain(signal, -30.0).samples, rate)
        assertEquals(normal.mostFrequent, quiet.mostFrequent)
        assertEquals(normal.lowest, quiet.lowest)
        assertEquals(normal.highest, quiet.highest)
    }

    @Test
    fun veryLoudClippedRecording() {
        // 20 dB übersteuert und hart auf ±1 begrenzt
        val signal = sentence(9)
        assertNoMismatch(listOf(mismatch("übersteuert", Speech.withGain(signal, 20.0, clip = true))))
    }

    @Test
    fun otherInputSampleRates() {
        val failures = mutableListOf<String?>()
        for (sampleRate in listOf(48_000, 16_000, 22_050)) {
            val (_, base, scale) = speakers[1]
            val segments = listOf(Pause(0.2)) + Speech.phrase(Speech.statement(base, 8, accent = 3, seed = 2), formantScale = scale) + Pause(0.2)
            failures += mismatch("$sampleRate Hz", Speech.synth(segments, rate = sampleRate, seed = 2))
        }
        assertNoMismatch(failures)
    }

    @Test
    fun twoSecondUtterance() {
        // Ein kurzer Satz, mit Stille auf genau 2 s aufgefüllt
        val (_, base, scale) = speakers[0]
        val phrase = Speech.phrase(Speech.statement(base, 7, accent = 2, seed = 11), formantScale = scale)
        val length = phrase.sumOf { it.seconds }
        assertTrue("Satz $length s", length < 1.9)
        val signal = Speech.synth(listOf(Pause(0.05)) + phrase + Pause(1.95 - length), seed = 11)
        assertEquals(2.0, signal.samples.size.toDouble() / rate, 0.03)
        assertTrue(Speech.expected(signal.truth).hasVoice)
        assertNoMismatch(listOf(mismatch("2 s", signal)))
    }

    /**
     * Zehn Minuten Sprache (Aussagesätze und Fragen einer Sprecherin, Pausen von 0,3–1,5 s,
     * dazwischen Knarren und Rauschen), Phrase für Phrase erzeugt und gestreamt: nie liegt die
     * ganze Aufnahme im Speicher. Gemessen wird nur die Analyse; die Erwartung kommt aus der Wahrheit.
     */
    @Test
    fun tenMinutesOfSpeech() {
        val random = java.util.Random(42)
        val pipeline = PitchAnalyzer.Pipeline()
        val truth = FloatList(60_000)
        var samples = 0L
        var analysisNs = 0L
        var phrase = 0
        while (samples < 600L * rate) {
            val base = 55.0 + 3 * random.nextGaussian().coerceIn(-1.5, 1.5)
            val syllables = if (phrase % 4 == 3) Speech.question(base, 5 + random.nextInt(4), seed = phrase) else
                Speech.statement(base, 5 + random.nextInt(8), accent = random.nextInt(3), seed = phrase)
            val segments = Speech.phrase(syllables, fryEnd = if (phrase % 5 == 0) 0.2 else 0.0, formantScale = 1.12) +
                Pause(0.3 + 1.2 * random.nextDouble())
            val quality = if (phrase % 7 == 0) Speech.BREATHY else Speech.MODAL
            val signal = Speech.synth(segments, quality = quality, seed = phrase, roomNoiseDb = -60.0)
            for (t in signal.truth) truth.add(t)
            val start = System.nanoTime()
            pipeline.feed(signal.samples, 0, signal.samples.size, rate)
            analysisNs += System.nanoTime() - start
            samples += signal.samples.size
            phrase++
        }
        val start = System.nanoTime()
        val result = pipeline.finish()
        analysisNs += System.nanoTime() - start
        val seconds = analysisNs / 1e9
        println("10 Minuten Sprache (%d Phrasen, 44,1 kHz) analysiert in %.2f s".format(phrase, seconds))
        val expected = Speech.expected(truth.toArray())
        Speech.mismatch(result, expected)?.let { fail(it) }
        assertTrue("stimmhaft ${result.voicedMs} ms, erwartet ${expected.voicedMs} ms", result.voicedMs > expected.voicedMs * 0.9)
        // Großzügig für langsame CI-Rechner
        assertTrue("zu langsam: $seconds s", seconds < 60)
    }
}
