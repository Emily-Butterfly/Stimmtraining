package de.emilybutterfly.stimmtraining.analysis

import de.emilybutterfly.stimmtraining.analysis.Signals.SAWTOOTH
import de.emilybutterfly.stimmtraining.analysis.Signals.concat
import de.emilybutterfly.stimmtraining.analysis.Signals.note
import de.emilybutterfly.stimmtraining.analysis.Signals.silence
import de.emilybutterfly.stimmtraining.analysis.Signals.syllable
import de.emilybutterfly.stimmtraining.analysis.Signals.tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class PitchAnalyzerTest {
    private val rate = 44_100

    private fun assertNotes(expectedMost: Int, expectedLow: Int, expectedHigh: Int, result: PitchResult) {
        val names = { n: Int? -> n?.let(Notes::name) }
        assertEquals("häufigste", names(expectedMost), names(result.mostFrequent))
        assertEquals("tiefste", names(expectedLow), names(result.lowest))
        assertEquals("höchste", names(expectedHigh), names(result.highest))
    }

    private fun assertNoVoice(result: PitchResult) {
        assertFalse(result.hasVoice)
        assertNull(result.mostFrequent)
        assertNull(result.lowest)
        assertNull(result.highest)
    }

    /** Median der erkannten Grundfrequenz über alle periodischen, lauten Rahmen. */
    private fun medianF0(samples: FloatArray, sampleRate: Int): Double {
        val pipeline = PitchAnalyzer.Pipeline()
        pipeline.feed(samples, 0, samples.size, sampleRate)
        val values = pipeline.tracker.f0.toArray().filter { it > 0f }.sorted()
        assertTrue("keine periodischen Rahmen", values.isNotEmpty())
        return values[values.size / 2].toDouble()
    }

    @Test
    fun pureSineIsA3() {
        val result = PitchAnalyzer.analyze(tone(2.0, rate, 220.0), rate)
        assertNotes(57, 57, 57, result)
        assertTrue(result.voicedMs in 1800L..2000L)
    }

    @Test
    fun harmonicRichSawtoothAt110HzIsA2() {
        val result = PitchAnalyzer.analyze(tone(2.0, rate, 110.0, harmonics = SAWTOOTH), rate)
        assertNotes(45, 45, 45, result)
    }

    @Test
    fun pulseTrainAt110HzIsA2() {
        // Alle Teiltöne gleich stark: schmale Pulse wie Stimmlippen-Schläge
        val result = PitchAnalyzer.analyze(tone(2.0, rate, 110.0, harmonics = DoubleArray(40) { 1.0 }), rate)
        assertNotes(45, 45, 45, result)
    }

    @Test
    fun strongSecondHarmonicDoesNotJumpAnOctaveUp() {
        // Schwacher Grundton, starker 2. Teilton (Formant bei 2·F0) – klassischer Oktavfehler von YIN
        val harmonics = doubleArrayOf(0.2, 1.0, 0.15, 0.3, 0.1, 0.1)
        val result = PitchAnalyzer.analyze(tone(2.0, rate, note(50), harmonics = harmonics), rate)
        assertNotes(50, 50, 50, result)
    }

    @Test
    fun highVoiceWithStrongSecondHarmonicAboveTheRange() {
        // D5 (587 Hz) mit kräftigem 2. Teilton bei 1175 Hz (oberhalb des Suchbereichs):
        // weder Oktavfehler noch als „über dem Suchbereich“ verworfen
        val harmonics = doubleArrayOf(0.2, 1.0, 0.15, 0.3)
        assertNotes(74, 74, 74, PitchAnalyzer.analyze(tone(2.0, rate, note(74), harmonics = harmonics), rate))
    }

    @Test
    fun whistlesAboveTheSearchRangeAreNotReportedAsSubharmonics() {
        for (hz in listOf(1300.0, 1500.0, 2000.0)) {
            val result = PitchAnalyzer.analyze(tone(1.5, rate, hz), rate)
            assertFalse("$hz Hz als ${result.mostFrequent?.let(Notes::name)}", result.hasVoice)
        }
    }

    @Test
    fun heldStepsGiveLowestHighestAndMostFrequent() {
        val samples = tone(4.0, rate, harmonics = SAWTOOTH) { t ->
            when {
                t < 0.6 -> note(55) // G3
                t < 2.0 -> note(57) // A3, am längsten
                t < 2.6 -> note(59) // B3
                t < 3.2 -> note(58) // A#3
                else -> note(60) // C4
            }
        }
        assertNotes(57, 55, 60, PitchAnalyzer.analyze(samples, rate))
    }

    @Test
    fun glideBetweenHeldNotesKeepsItsEnds() {
        val samples = tone(2.5, rate, harmonics = SAWTOOTH) { t ->
            val semis = when {
                t < 0.5 -> 55.0
                t < 2.0 -> 55.0 + 5.0 * (t - 0.5) / 1.5
                else -> 60.0
            }
            440.0 * 2.0.pow((semis - 69) / 12)
        }
        val result = PitchAnalyzer.analyze(samples, rate)
        assertEquals("G3", Notes.name(result.lowest!!))
        assertEquals("C4", Notes.name(result.highest!!))
    }

    @Test
    fun speechLikeSyllablesMostlyA3WithShortExcursions() {
        val parts = mutableListOf<FloatArray>()
        val pattern = listOf(57, 57, 54, 57, 57, 57, 62, 57, 57, 54, 57, 57, 62, 57, 57, 57, 57, 57)
        pattern.forEachIndexed { i, midi ->
            val loud = if (i % 3 == 0) 0.6 else 0.35
            parts += syllable(0.28, rate, note(midi), loud)
            parts += Signals.whiteNoise(0.09, rate, 0.002, seed = i) // Atempause mit Raumrauschen
        }
        val result = PitchAnalyzer.analyze(concat(*parts.toTypedArray()), rate)
        assertNotes(57, 54, 62, result)
    }

    @Test
    fun shortOctaveGlitchesDoNotBecomeTheHighestNote() {
        // Dreimal 30 ms auf der Oktave mitten in einem gehaltenen A3
        val samples = tone(3.0, rate, harmonics = SAWTOOTH) { t ->
            val glitch = listOf(0.7, 1.5, 2.3).any { t >= it && t < it + 0.03 }
            if (glitch) 440.0 else 220.0
        }
        assertNotes(57, 57, 57, PitchAnalyzer.analyze(samples, rate))
    }

    @Test
    fun silenceHasNoNotes() {
        val result = PitchAnalyzer.analyze(silence(3.0, rate), rate)
        assertNoVoice(result)
        assertEquals(0L, result.voicedMs)
    }

    @Test
    fun emptyInputHasNoNotes() {
        assertNoVoice(PitchAnalyzer.analyze(FloatArray(0), rate))
    }

    @Test
    fun whiteNoiseHasNoNotes() {
        assertNoVoice(PitchAnalyzer.analyze(Signals.whiteNoise(3.0, rate, 0.3), rate))
    }

    @Test
    fun breathyNoiseHasNoNotes() {
        val breath = concat(
            Signals.breathNoise(1.5, rate, centerHz = 600.0, bandwidthHz = 150.0, amplitude = 0.4),
            Signals.breathNoise(1.5, rate, centerHz = 1500.0, bandwidthHz = 200.0, amplitude = 0.4),
        )
        assertNoVoice(PitchAnalyzer.analyze(breath, rate))
    }

    @Test
    fun veryShortVoiceHasNoNotesButReportsItsDuration() {
        val result = PitchAnalyzer.analyze(concat(silence(0.5, rate), tone(0.3, rate, 220.0), silence(1.0, rate)), rate)
        assertNoVoice(result)
        assertTrue("voicedMs=${result.voicedMs}", result.voicedMs in 100L until 500L)
    }

    @Test
    fun dcOffsetIsTolerated() {
        val samples = tone(2.0, rate, 220.0, amplitude = 0.4).map { it + 0.3f }.toFloatArray()
        val withSilence = concat(FloatArray(rate) { 0.3f }, samples, FloatArray(rate) { 0.3f })
        val result = PitchAnalyzer.analyze(withSilence, rate)
        assertNotes(57, 57, 57, result)
        // Der Gleichanteil allein zählt nicht als Stimme (Rahmen am Rand des Tons zählen mit)
        assertTrue("voicedMs=${result.voicedMs}", result.voicedMs <= 2100L)
    }

    @Test
    fun clippingIsTolerated() {
        val clipped = tone(2.0, rate, 220.0, amplitude = 3.0, harmonics = SAWTOOTH).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        assertNotes(57, 57, 57, PitchAnalyzer.analyze(clipped, rate))
    }

    @Test
    fun quietPartsCountButFaintBackgroundDoesNot() {
        val samples = concat(
            tone(1.5, rate, note(57), amplitude = 0.8, harmonics = SAWTOOTH), // laut
            tone(1.5, rate, note(60), amplitude = 0.04, harmonics = SAWTOOTH), // 26 dB leiser
            tone(1.5, rate, note(64), amplitude = 0.8 * 10.0.pow(-45.0 / 20), harmonics = SAWTOOTH), // 45 dB leiser
        )
        val result = PitchAnalyzer.analyze(samples, rate)
        assertEquals("A3", Notes.name(result.lowest!!))
        assertEquals("C4", Notes.name(result.highest!!))
    }

    @Test
    fun lowMaleVoiceAt85Hz() {
        val samples = tone(2.0, rate, 85.0, harmonics = SAWTOOTH)
        assertEquals(85.0, medianF0(samples, rate), 85.0 * 0.005)
        val expected = Notes.fromFrequency(85.0)
        assertNotes(expected, expected, expected, PitchAnalyzer.analyze(samples, rate))
    }

    @Test
    fun highVoiceAt400Hz() {
        val samples = tone(2.0, rate, 400.0, harmonics = SAWTOOTH)
        assertEquals(400.0, medianF0(samples, rate), 400.0 * 0.005)
        assertNotes(67, 67, 67, PitchAnalyzer.analyze(samples, rate))
    }

    @Test
    fun searchRangeEndsAreDetected() {
        assertEquals(62.0, medianF0(tone(1.5, rate, 62.0), rate), 62.0 * 0.005)
        assertEquals(950.0, medianF0(tone(1.5, rate, 950.0), rate), 950.0 * 0.01)
    }

    @Test
    fun commonInputSampleRates() {
        for (sampleRate in listOf(44_100, 48_000, 16_000, 22_050, 8_000)) {
            val samples = tone(2.0, sampleRate, 220.0, harmonics = SAWTOOTH)
            assertEquals("$sampleRate Hz", 220.0, medianF0(samples, sampleRate), 220.0 * 0.005)
            val result = PitchAnalyzer.analyze(samples, sampleRate)
            assertEquals("$sampleRate Hz", 57, result.mostFrequent)
            assertEquals("$sampleRate Hz", 57, result.lowest)
            assertEquals("$sampleRate Hz", 57, result.highest)
        }
    }

    @Test
    fun vibratoOfHalfASemitoneStaysOnTheNote() {
        val samples = tone(3.0, rate, harmonics = SAWTOOTH) { t -> 220.0 * 2.0.pow(0.5 * sin(2 * PI * 5.5 * t) / 12) }
        assertNotes(57, 57, 57, PitchAnalyzer.analyze(samples, rate))
    }

    @Test
    fun voiceModelThroughPhoneMicrophoneKeepsTheOctave() {
        // Tiefe Männerstimme bis Kopfstimme; Vokale mit F1 nahe 2·F0 oder 3·F0 sind die typischen Oktavfallen
        val voices = listOf(43 to "G2", 45 to "A2", 48 to "C3", 52 to "E3", 55 to "G3", 57 to "A3", 62 to "D4", 69 to "A4")
        val vowels = mapOf("a" to Signals.VOWEL_A, "i" to Signals.VOWEL_I, "u" to Signals.VOWEL_U)
        for ((midi, name) in voices) {
            for ((vowel, formants) in vowels) {
                val samples = Signals.voice(1.5, rate, note(midi), formants, seed = midi)
                val result = PitchAnalyzer.analyze(samples, rate)
                val label = "$name /$vowel/"
                assertEquals(label, name, result.mostFrequent?.let(Notes::name))
                assertEquals(label, name, result.lowest?.let(Notes::name))
                assertEquals(label, name, result.highest?.let(Notes::name))
            }
        }
    }

    @Test
    fun breathyVoiceIsStillRecognised() {
        val samples = Signals.voice(2.0, rate, note(55), Signals.VOWEL_A, breath = 0.15, jitter = 0.02)
        val result = PitchAnalyzer.analyze(samples, rate)
        assertEquals(55, result.mostFrequent)
        assertTrue("voicedMs=${result.voicedMs}", result.voicedMs > 1000)
    }

    @Test
    fun noOctaveErrorsAndAccurateAcrossTheRange() {
        // Sinus, Sägezahn und Pulsfolge (alle Teiltöne gleich stark) von B1 (62 Hz) bis B5 (988 Hz)
        val timbres = mapOf("Sinus" to Signals.SINE, "Sägezahn" to SAWTOOTH, "Pulse" to DoubleArray(40) { 1.0 })
        for ((timbre, harmonics) in timbres) {
            for (midi in 35..83) {
                val measured = medianF0(tone(0.6, rate, note(midi), harmonics = harmonics), rate)
                val error = Notes.semitones(measured) - midi
                assertTrue("$timbre ${Notes.name(midi)}: $measured Hz", abs(error) < 0.1)
            }
        }
    }
}
