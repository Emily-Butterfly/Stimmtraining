package de.emilybutterfly.stimmtraining.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zehn Minuten „Sprache“ bei 44,1 kHz, blockweise erzeugt und gestreamt (wie vom Decoder),
 * damit nie die ganze Aufnahme im Speicher liegt. Gemessen wird nur die Analyse.
 */
class PitchPerformanceTest {
    @Test
    fun tenMinutesAnalyseQuicklyWithBoundedMemory() {
        val rate = 44_100
        val total = rate * 600
        // Eine Periode eines obertonreichen Klangs als Tabelle, damit die Erzeugung kaum Zeit kostet
        val table = FloatArray(4096) { i ->
            val p = 2 * PI * i / 4096
            (1..12).sumOf { k -> sin(k * p) / k }.toFloat() * 0.3f
        }
        val melody = intArrayOf(57, 57, 55, 57, 59, 57, 60, 57, 54, 57)
        val block = FloatArray(2048)
        val random = Random(3)
        val pipeline = PitchAnalyzer.Pipeline()
        var phase = 0.0
        var produced = 0
        var analysisNs = 0L
        while (produced < total) {
            val n = minOf(block.size, total - produced)
            for (i in 0 until n) {
                val t = (produced + i).toDouble() / rate
                // Silben von 300 ms, dazwischen 100 ms Pause mit Raumrauschen (-51 dBFS, über der
                // absoluten Untergrenze, damit auch dort die volle Periodensuche läuft)
                val syllable = (t / 0.4).toInt()
                val voiced = t % 0.4 < 0.3
                phase += Notes.frequency(melody[syllable % melody.size]) / rate
                if (phase >= 1.0) phase -= 1.0
                val v = if (voiced) table[(phase * 4096).toInt() and 4095] else 0f
                block[i] = v + (random.nextFloat() - 0.5f) * 0.01f
            }
            val start = System.nanoTime()
            pipeline.feed(block, 0, n, rate)
            analysisNs += System.nanoTime() - start
            produced += n
        }
        val start = System.nanoTime()
        val result = pipeline.finish()
        analysisNs += System.nanoTime() - start
        val seconds = analysisNs / 1e9
        println("10 Minuten Audio (44,1 kHz) analysiert in %.2f s".format(seconds))
        assertEquals(57, result.mostFrequent)
        assertEquals(54, result.lowest)
        assertEquals(60, result.highest)
        assertTrue(result.voicedMs > 400_000)
        // Großzügig für langsame CI-Rechner; auf dem Entwicklungsrechner deutlich schneller
        assertTrue("zu langsam: $seconds s", seconds < 60)
    }
}
