package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Erzeugt Testsignale im Code (keine Binärdateien). */
object Signals {
    /** Gleichmäßig abnehmende Teiltöne wie bei einem bandbegrenzten Sägezahn. */
    val SAWTOOTH = DoubleArray(40) { 1.0 / (it + 1) }
    val SINE = doubleArrayOf(1.0)

    /**
     * Phasenstetiger Ton mit zeitabhängiger Tonhöhe [pitchHz] (Sekunden → Hz) und Teiltönen
     * [harmonics] (Amplituden relativ); Teiltöne oberhalb 0,45 · Abtastrate entfallen.
     * Das Ergebnis ist auf die Spitzenamplitude [amplitude] skaliert.
     */
    fun tone(
        seconds: Double,
        rate: Int,
        amplitude: Double = 0.5,
        harmonics: DoubleArray = SINE,
        pitchHz: (Double) -> Double,
    ): FloatArray {
        val n = (seconds * rate).toInt()
        val out = DoubleArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val f = pitchHz(i.toDouble() / rate)
            val maxHarmonic = min(harmonics.size, (0.45 * rate / f).toInt())
            var v = 0.0
            for (k in 0 until maxHarmonic) v += harmonics[k] * sin((k + 1) * phase)
            out[i] = v
            phase += 2 * PI * f / rate
            if (phase > 2 * PI) phase -= 2 * PI
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        return FloatArray(n) { (out[it] / peak * amplitude).toFloat() }
    }

    fun tone(seconds: Double, rate: Int, hz: Double, amplitude: Double = 0.5, harmonics: DoubleArray = SINE) =
        tone(seconds, rate, amplitude, harmonics) { hz }

    fun note(midi: Int) = Notes.frequency(midi)

    fun silence(seconds: Double, rate: Int) = FloatArray((seconds * rate).toInt())

    fun whiteNoise(seconds: Double, rate: Int, amplitude: Double, seed: Int = 1): FloatArray {
        val random = Random(seed)
        return FloatArray((seconds * rate).toInt()) { ((random.nextDouble() * 2 - 1) * amplitude).toFloat() }
    }

    /** Rauschen durch einen Resonator (Mittenfrequenz, Bandbreite), wie Hauchen auf einem Vokal. */
    fun breathNoise(seconds: Double, rate: Int, centerHz: Double, bandwidthHz: Double, amplitude: Double): FloatArray {
        val noise = whiteNoise(seconds, rate, 1.0, seed = 7)
        val r = exp(-PI * bandwidthHz / rate)
        val a1 = 2 * r * cos(2 * PI * centerHz / rate)
        val a2 = -r * r
        var y1 = 0.0
        var y2 = 0.0
        val out = DoubleArray(noise.size)
        for (i in noise.indices) {
            val y = noise[i] + a1 * y1 + a2 * y2
            y2 = y1
            y1 = y
            out[i] = y
        }
        val peak = out.maxOf { kotlin.math.abs(it) }
        return FloatArray(out.size) { (out[it] / peak * amplitude).toFloat() }
    }

    /** Silbe: Ton mit weichem Ein- und Ausschwingen (20 ms). */
    fun syllable(seconds: Double, rate: Int, hz: Double, amplitude: Double, harmonics: DoubleArray = SAWTOOTH): FloatArray {
        val drift = 0.15 // leichte Sprechmelodie innerhalb der Silbe (Halbtöne)
        val samples = tone(seconds, rate, amplitude, harmonics) { t -> hz * 2.0.pow(drift * sin(PI * t / seconds) / 12) }
        val ramp = (0.02 * rate).toInt()
        for (i in samples.indices) {
            val edge = min(i, samples.size - 1 - i)
            if (edge < ramp) samples[i] *= (edge.toFloat() / ramp)
        }
        return samples
    }

    /** Vokale als Formanten (Frequenz, Bandbreite) einer Erwachsenenstimme. */
    val VOWEL_A = listOf(730.0 to 90.0, 1090.0 to 110.0, 2440.0 to 170.0)
    val VOWEL_I = listOf(270.0 to 60.0, 2290.0 to 100.0, 3010.0 to 150.0)
    val VOWEL_U = listOf(300.0 to 60.0, 870.0 to 90.0, 2240.0 to 150.0)

    /**
     * Einfaches Stimmmodell: Glottispulse mit Jitter (Periodenschwankung) und Shimmer
     * (Amplitudenschwankung), Spektralabfall wie bei der Glottis (-12 dB/Oktave) samt
     * Lippenabstrahlung (+6 dB/Oktave), drei Formanten, Hauchrauschen und zum Schluss ein
     * Hochpass wie bei Handy-Mikrofonen (150 Hz), der tiefe Grundtöne deutlich abschwächt.
     */
    fun voice(
        seconds: Double,
        rate: Int,
        hz: Double,
        formants: List<Pair<Double, Double>>,
        amplitude: Double = 0.5,
        jitter: Double = 0.01,
        shimmer: Double = 0.05,
        breath: Double = 0.02,
        seed: Int = 11,
    ): FloatArray {
        val random = Random(seed)
        val n = (seconds * rate).toInt()
        val signal = DoubleArray(n)
        var next = 0.0
        while (next < n) {
            signal[next.toInt()] = 1 + shimmer * (random.nextDouble() * 2 - 1)
            next += rate / hz * (1 + jitter * (random.nextDouble() * 2 - 1))
        }
        // Glottis: zwei Tiefpässe erster Ordnung bei 100 Hz; Lippen: erste Ableitung
        val pole = exp(-2 * PI * 100.0 / rate)
        var y1 = 0.0
        var y2 = 0.0
        var previous = 0.0
        for (i in 0 until n) {
            y1 = signal[i] + pole * y1
            y2 = y1 + pole * y2
            signal[i] = y2 - previous
            previous = y2
        }
        for ((f, bw) in formants) resonate(signal, rate, f, bw)
        val peak = signal.maxOf { kotlin.math.abs(it) }
        for (i in 0 until n) signal[i] = signal[i] / peak + breath * (random.nextDouble() * 2 - 1)
        highPass(signal, rate, 150.0)
        val peak2 = signal.maxOf { kotlin.math.abs(it) }
        return FloatArray(n) { (signal[it] / peak2 * amplitude).toFloat() }
    }

    /** Resonator 2. Ordnung mit Verstärkung 1 bei 0 Hz (an Ort und Stelle). */
    private fun resonate(signal: DoubleArray, rate: Int, hz: Double, bandwidth: Double) {
        val r = exp(-PI * bandwidth / rate)
        val a1 = 2 * r * cos(2 * PI * hz / rate)
        val a2 = -r * r
        val gain = 1 - a1 - a2
        var y1 = 0.0
        var y2 = 0.0
        for (i in signal.indices) {
            val y = gain * signal[i] + a1 * y1 + a2 * y2
            y2 = y1
            y1 = y
            signal[i] = y
        }
    }

    private fun highPass(signal: DoubleArray, rate: Int, hz: Double) {
        val data = FloatArray(signal.size) { signal[it].toFloat() }
        Biquad.highPass(rate, hz).process(data, data.size)
        for (i in signal.indices) signal[i] = data[i].toDouble()
    }

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    fun mix(a: FloatArray, b: FloatArray): FloatArray = FloatArray(maxOf(a.size, b.size)) {
        (if (it < a.size) a[it] else 0f) + (if (it < b.size) b[it] else 0f)
    }
}
