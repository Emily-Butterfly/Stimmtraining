package de.emilybutterfly.stimmtraining.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    /** Wandelt in kleinen, ungleichen Blöcken um, wie sie vom Decoder kommen. */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val resampler = Resampler(from, to)
        val out = FloatList()
        var offset = 0
        var block = 1
        while (offset < input.size) {
            val n = minOf(block, input.size - offset)
            val produced = resampler.process(input, offset, n)
            for (i in 0 until produced) out.add(resampler.output[i])
            offset += n
            block = block * 3 % 2000 + 1
        }
        return out.toArray()
    }

    /** Amplitude der Frequenz [hz] im mittleren Teil des Signals (Projektion auf Sinus und Kosinus). */
    private fun amplitudeAt(signal: FloatArray, rate: Int, hz: Double): Double {
        val from = signal.size / 4
        val to = signal.size * 3 / 4
        var s = 0.0
        var c = 0.0
        for (i in from until to) {
            val w = 2 * PI * hz * i / rate
            s += signal[i] * sin(w)
            c += signal[i] * cos(w)
        }
        return 2 * sqrt(s * s + c * c) / (to - from)
    }

    private fun sine(hz: Double, rate: Int, seconds: Double = 1.0) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test
    fun keepsLengthFrequencyAndLevel() {
        for (from in listOf(44_100, 48_000, 22_050, 16_000, 11_025, 12_345)) {
            val out = resample(sine(440.0, from), from, 8000)
            // Am Ende fehlt höchstens die halbe Filterlänge (gut 1 ms), da nicht nachgefüllt wird
            assertEquals("$from Hz", 8000.0, out.size.toDouble(), 20.0)
            assertEquals("$from Hz", 0.5, amplitudeAt(out, 8000, 440.0), 0.01)
        }
    }

    @Test
    fun keepsTheTimeAxis() {
        // Ein Klick bei 0,5 s bleibt bei 0,5 s
        val input = FloatArray(44_100).also { it[22_050] = 1f }
        val out = resample(input, 44_100, 8000)
        val peak = out.indices.maxBy { out[it] }
        assertEquals(4000, peak)
    }

    @Test
    fun suppressesAliasing() {
        // 6 kHz würde bei 8 kHz als 2 kHz zurückgespiegelt
        val out = resample(sine(6000.0, 44_100), 44_100, 8000)
        assertTrue(amplitudeAt(out, 8000, 2000.0) < 0.5 * 0.001)
        val out48 = resample(sine(5000.0, 48_000), 48_000, 8000)
        assertTrue(amplitudeAt(out48, 8000, 3000.0) < 0.5 * 0.01)
    }

    @Test
    fun upsamplesLowRates() {
        val out = resample(sine(300.0, 6000), 6000, 8000)
        assertEquals(8000.0, out.size.toDouble(), 20.0)
        assertEquals(0.5, amplitudeAt(out, 8000, 300.0), 0.01)
    }

    @Test
    fun sameRatePassesThroughUnchanged() {
        val input = sine(440.0, 8000)
        val out = resample(input, 8000, 8000)
        assertEquals(input.toList(), out.toList())
    }
}
