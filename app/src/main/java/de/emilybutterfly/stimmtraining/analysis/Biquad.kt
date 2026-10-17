package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** IIR-Filter 2. Ordnung (Biquad, Formeln nach dem „Audio EQ Cookbook“), arbeitet blockweise an Ort und Stelle. */
internal class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /** Filtert die ersten [count] Werte von [data]; der Zustand bleibt für den nächsten Block erhalten. */
    fun process(data: FloatArray, count: Int) {
        for (i in 0 until count) {
            val x = data[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            data[i] = y.toFloat()
        }
    }

    companion object {
        /** Butterworth-Güte für einen einzelnen Biquad. */
        const val BUTTERWORTH_Q = 0.7071067811865476

        fun highPass(sampleRate: Int, cutoffHz: Double, q: Double = BUTTERWORTH_Q): Biquad {
            val w0 = 2 * PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosW = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 + cosW) / 2 / a0, -(1 + cosW) / a0, (1 + cosW) / 2 / a0, -2 * cosW / a0, (1 - alpha) / a0)
        }

        fun lowPass(sampleRate: Int, cutoffHz: Double, q: Double = BUTTERWORTH_Q): Biquad {
            val w0 = 2 * PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosW = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 - cosW) / 2 / a0, (1 - cosW) / a0, (1 - cosW) / 2 / a0, -2 * cosW / a0, (1 - alpha) / a0)
        }

        /** Butterworth-Tiefpass 4. Ordnung als zwei Biquads. */
        fun lowPass4(sampleRate: Int, cutoffHz: Double): List<Biquad> = listOf(
            lowPass(sampleRate, cutoffHz, 1 / (2 * cos(PI / 8))),
            lowPass(sampleRate, cutoffHz, 1 / (2 * cos(3 * PI / 8))),
        )
    }
}
