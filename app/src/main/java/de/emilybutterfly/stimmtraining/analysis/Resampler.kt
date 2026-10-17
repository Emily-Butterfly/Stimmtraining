package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Wandelt Mono-PCM gestreamt von [inputRate] auf [outputRate] um, für beliebige Ratenpaare
 * (8 / 16 / 22,05 / 44,1 / 48 kHz …). Davor liegt ein gefensterter Sinc-Tiefpass (Blackman) bei
 * 40 % der kleineren Rate, damit beim Verkleinern nichts oberhalb der neuen Nyquist-Frequenz
 * zurückgespiegelt wird. Die Filterkoeffizienten liegen als Polyphasen-Tabelle vor; bei üblichen
 * Ratenverhältnissen (44100 → 8000 = 441/80) ist jede Phase exakt, sonst auf 1/512 Sample genau.
 */
internal class Resampler(val inputRate: Int, val outputRate: Int) {
    private val passThrough = inputRate == outputRate

    /** Schrittweite pro Ausgabewert als Bruch [step]/[den] Eingabewerte. */
    private val step: Int
    private val den: Int
    private val phases: Int
    private val halfTaps: Int
    private val taps: Int
    private val table: FloatArray

    // Eingabepuffer: Werte ab dem ältesten noch benötigten; Position des nächsten Ausgabewerts
    // ist index + frac/den (index bezogen auf den Pufferanfang).
    private var buffer: FloatArray
    private var length: Int
    private var index: Int
    private var frac = 0

    /** Ausgabe des letzten [process]-Aufrufs; wird wiederverwendet. */
    var output = FloatArray(0)
        private set

    init {
        require(inputRate > 0 && outputRate > 0) { "Ungültige Abtastrate" }
        val g = gcd(inputRate, outputRate)
        step = inputRate / g
        den = outputRate / g
        phases = min(den, MAX_PHASES)

        // Grenzfrequenz in Zyklen pro Eingabewert; Halbbreite in Eingabewerten.
        val cutoff = CUTOFF * min(inputRate, outputRate) / inputRate
        val halfWidth = ZERO_CROSSINGS / (2 * cutoff)
        halfTaps = ceil(halfWidth).toInt()
        taps = 2 * halfTaps
        table = FloatArray((phases + 1) * taps)
        for (p in 0..phases) {
            val shift = p.toDouble() / phases
            var sum = 0.0
            for (k in 0 until taps) {
                // Abstand des Eingabewerts zur Ausgabeposition
                val t = (k - halfTaps + 1) - shift
                val h = if (abs(t) >= halfWidth) 0.0 else 2 * cutoff * sinc(2 * cutoff * t) * blackman(t / halfWidth)
                table[p * taps + k] = h.toFloat()
                sum += h
            }
            // Verstärkung 1 für Gleichanteil in jeder Phase
            for (k in 0 until taps) table[p * taps + k] = (table[p * taps + k] / sum).toFloat()
        }
        // Vor dem ersten Wert Stille annehmen, damit das Filter sofort zentriert arbeitet;
        // der erste Ausgabewert liegt genau auf dem ersten Eingabewert.
        buffer = FloatArray(taps + 4096)
        length = halfTaps
        index = halfTaps
    }

    /** Wandelt [count] Werte ab [offset] um; liefert die Anzahl neuer Werte in [output]. */
    fun process(input: FloatArray, offset: Int, count: Int): Int {
        if (passThrough) {
            if (output.size < count) output = FloatArray(count)
            System.arraycopy(input, offset, output, 0, count)
            return count
        }
        append(input, offset, count)

        val maxOut = ((length.toLong() * den) / step + 2).toInt()
        if (output.size < maxOut) output = FloatArray(maxOut)
        val out = output
        val buf = buffer
        var produced = 0
        while (index + halfTaps < length) {
            val phase = if (phases == den) frac else ((frac.toLong() * phases * 2 + den) / (2L * den)).toInt()
            val row = phase * taps
            val start = index - halfTaps + 1
            // Vier unabhängige Teilsummen; taps ist gerade, der Rest wird einzeln addiert.
            var acc0 = 0f
            var acc1 = 0f
            var acc2 = 0f
            var acc3 = 0f
            var k = 0
            while (k + 3 < taps) {
                acc0 += buf[start + k] * table[row + k]
                acc1 += buf[start + k + 1] * table[row + k + 1]
                acc2 += buf[start + k + 2] * table[row + k + 2]
                acc3 += buf[start + k + 3] * table[row + k + 3]
                k += 4
            }
            while (k < taps) {
                acc0 += buf[start + k] * table[row + k]
                k++
            }
            out[produced++] = (acc0 + acc1) + (acc2 + acc3)
            frac += step
            index += frac / den
            frac %= den
        }
        return produced
    }

    private fun append(input: FloatArray, offset: Int, count: Int) {
        // Nicht mehr benötigte Werte vorne verwerfen
        val drop = min(max(0, index - halfTaps + 1), length)
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, length - drop)
            length -= drop
            index -= drop
        }
        if (length + count > buffer.size) buffer = buffer.copyOf(max(length + count, buffer.size * 2))
        System.arraycopy(input, offset, buffer, length, count)
        length += count
    }

    private companion object {
        const val CUTOFF = 0.4
        const val ZERO_CROSSINGS = 8
        const val MAX_PHASES = 512

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Blackman-Fenster für u in -1..1. */
        fun blackman(u: Double): Double = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
