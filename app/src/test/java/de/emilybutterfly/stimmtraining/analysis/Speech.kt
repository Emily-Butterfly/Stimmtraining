package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sprachähnliche Testsignale mit bekannter Wahrheit (unabhängig vom Stimmmodell in [Signals]):
 * Glottisfluss nach Rosenberg mit Jitter und Shimmer, Lippenabstrahlung (Ableitung), Vokaltrakt
 * als Kaskade aus vier Formant-Resonatoren mit fließenden Übergängen, Hauchrauschen im Takt der
 * Glottis, Knarrstimme (unregelmäßige tiefe Pulse), Flüstern, Reibelaute und Pausen.
 * Zu jedem Signal gibt es den wahren Grundton je 10-ms-Rahmen (0 = keine Stimme im Suchbereich).
 */
object Speech {
    /** Formanten F1…F4 (Hz) mit Bandbreiten (Hz). */
    class Vowel(val name: String, val f: DoubleArray, val bw: DoubleArray) {
        fun scaled(factor: Double) = Vowel(name, DoubleArray(4) { f[it] * factor }, bw)
        override fun toString() = name
    }

    // Erwachsene Männerstimme (Peterson & Barney); Frauen und hohe Kehlkopfstellung etwa 15–20 % höher
    val A = Vowel("a", doubleArrayOf(730.0, 1090.0, 2440.0, 3400.0), doubleArrayOf(80.0, 90.0, 120.0, 200.0))
    val E = Vowel("e", doubleArrayOf(400.0, 2000.0, 2550.0, 3500.0), doubleArrayOf(60.0, 100.0, 120.0, 200.0))
    val I = Vowel("i", doubleArrayOf(270.0, 2290.0, 3010.0, 3700.0), doubleArrayOf(50.0, 100.0, 140.0, 200.0))
    val O = Vowel("o", doubleArrayOf(450.0, 800.0, 2830.0, 3500.0), doubleArrayOf(60.0, 80.0, 120.0, 200.0))
    val U = Vowel("u", doubleArrayOf(300.0, 870.0, 2240.0, 3400.0), doubleArrayOf(50.0, 80.0, 120.0, 200.0))
    val VOWELS = listOf(A, E, I, O, U)

    /** Stimmqualität: Jitter und Shimmer als Standardabweichung je Periode (relativ). */
    data class Quality(
        val jitter: Double = 0.005,
        val shimmer: Double = 0.04,
        /** Hauchrauschen relativ zur Glottis-Anregung. */
        val breath: Double = 0.05,
        /** Anteil der offenen Phase an der Periode (0,5 gepresst … 0,9 behaucht). */
        val openQuotient: Double = 0.6,
        /** Faktor für die Bandbreite von F1: bei offener Glottis (behaucht) dämpft die Lunge F1 stärker. */
        val f1Damping: Double = 1.0,
    )

    val MODAL = Quality()
    val BREATHY = Quality(jitter = 0.008, shimmer = 0.06, breath = 0.3, openQuotient = 0.85, f1Damping = 2.5)
    val ROUGH = Quality(jitter = 0.012, shimmer = 0.08, breath = 0.08)

    sealed class Segment(val seconds: Double)

    /** Stimmhaft; [pitch] liefert F0 in Hz zur Zeit t (Sekunden ab Abschnittsbeginn). */
    class Voice(seconds: Double, val vowel: Vowel, val levelDb: Double = 0.0, val pitch: (Double) -> Double) : Segment(seconds)

    /** Knarrstimme: unregelmäßige Pulse um [meanHz], abwechselnd stark und schwach (unterhalb des Suchbereichs). */
    class Fry(seconds: Double, val vowel: Vowel, val levelDb: Double = -10.0, val meanHz: Double = 40.0) : Segment(seconds)

    /** Geflüsterter Vokal: Rauschen durch den Vokaltrakt, keine Stimmlippenschwingung. */
    class Whisper(seconds: Double, val vowel: Vowel, val levelDb: Double = 0.0) : Segment(seconds)

    /** Stimmloser Reibelaut (s, sch, f): hochfrequentes Rauschen. */
    class Fricative(seconds: Double, val levelDb: Double = -12.0) : Segment(seconds)

    class Pause(seconds: Double) : Segment(seconds)

    /** Signal samt Wahrheit: F0 je 10-ms-Rahmen (Rahmenmitte bei k · 10 ms + 20 ms), 0 = keine Stimme. */
    class Signal(val samples: FloatArray, val rate: Int, val truth: FloatArray)

    fun held(midi: Double, driftSemitones: Double = 0.15): (Double) -> Double =
        { t -> hz(midi + driftSemitones * sin(2 * PI * 0.7 * t)) }

    fun glide(from: Double, to: Double, seconds: Double): (Double) -> Double =
        { t -> hz(from + (to - from) * min(1.0, t / seconds)) }

    fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12)

    /** Nasal (m, n): tiefer Formant, gedämpfte höhere; stimmhaft wie der Vokal. */
    val NASAL = Vowel("m", doubleArrayOf(260.0, 1100.0, 2300.0, 3400.0), doubleArrayOf(60.0, 250.0, 250.0, 300.0))

    /** Anlaut einer Silbe. */
    enum class Onset { NONE, NASAL, FRICATIVE, PLOSIVE, PAUSE }

    /** Silbe mit Tonhöhenverlauf von [from] nach [to] (MIDI, gebrochen), Pegel relativ in dB. */
    class Syllable(
        val vowel: Vowel,
        val seconds: Double,
        val from: Double,
        val to: Double = from,
        val levelDb: Double = 0.0,
        val onset: Onset = Onset.NONE,
    )

    /**
     * Abschnitte einer Phrase: Anlaute (Nasal stimmhaft auf der Anfangstonhöhe, Reibelaut,
     * Verschlusslaut mit Lösungsgeräusch, Pause), dann der Vokal mit gleitender Tonhöhe.
     * [fryEnd] hängt Knarrstimme an das Phrasenende.
     */
    fun phrase(syllables: List<Syllable>, fryEnd: Double = 0.0, formantScale: Double = 1.0): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in syllables) {
            when (s.onset) {
                Onset.NONE -> Unit
                Onset.NASAL -> out += Voice(0.07, NASAL.scaled(formantScale), s.levelDb - 8, held(s.from, 0.0))
                Onset.FRICATIVE -> out += Fricative(0.09, s.levelDb - 14)
                Onset.PLOSIVE -> {
                    out += Pause(0.05)
                    out += Fricative(0.02, s.levelDb - 10)
                }
                Onset.PAUSE -> out += Pause(0.15)
            }
            out += Voice(s.seconds, s.vowel.scaled(formantScale), s.levelDb, glide(s.from, s.to, s.seconds))
        }
        if (fryEnd > 0) out += Fry(fryEnd, syllables.last().vowel.scaled(formantScale), syllables.last().levelDb - 10)
        return out
    }

    /**
     * Aussagesatz mit [count] Silben um [base] (MIDI): Einsatz etwas über der Grundlage, Akzent
     * auf Silbe [accent] (+[accentSemitones]), danach Abfall bis 3 Halbtöne unter [base];
     * die letzte Silbe ist gedehnt. Lauter auf dem Akzent, leiser zum Ende.
     */
    fun statement(base: Double, count: Int, accent: Int, accentSemitones: Double = 4.0, seed: Int = 1): List<Syllable> {
        val random = java.util.Random(seed.toLong())
        val vowels = listOf(A, E, I, O, U)
        val onsets = Onset.values()
        var pitch = base + 1.5
        return List(count) { i ->
            val last = i == count - 1
            val target = when {
                i == accent -> base + accentSemitones
                i < accent -> base + 1.5 - 0.3 * i
                else -> base - 3.0 * (i - accent) / (count - 1 - accent).coerceAtLeast(1)
            }
            val from = if (i == accent) pitch + 0.5 else pitch
            val s = Syllable(
                vowel = vowels[random.nextInt(vowels.size)],
                seconds = if (last) 0.32 else 0.14 + 0.08 * random.nextDouble(),
                from = from,
                to = target,
                levelDb = when {
                    i == accent -> 0.0
                    last -> -9.0
                    else -> -4.0 - 3 * random.nextDouble()
                },
                onset = if (i == 0) Onset.NONE else onsets[random.nextInt(onsets.size - 1)],
            )
            pitch = target
            s
        }
    }

    /** Entscheidungsfrage: wie [statement], aber die letzten beiden Silben steigen um [rise] Halbtöne. */
    fun question(base: Double, count: Int, rise: Double = 6.0, seed: Int = 1): List<Syllable> {
        val s = statement(base, count, accent = 1, accentSemitones = 2.0, seed = seed).toMutableList()
        val n = s.size
        s[n - 2] = Syllable(s[n - 2].vowel, s[n - 2].seconds, base, base + rise / 3, -3.0, s[n - 2].onset)
        s[n - 1] = Syllable(s[n - 1].vowel, 0.3, base + rise / 3, base + rise, -2.0, s[n - 1].onset)
        return s
    }

    /**
     * Erzeugt die Abschnitte nacheinander bei [rate]; Pegel in dB relativ, am Ende auf die
     * Spitzenamplitude [peak] normiert. [micHighPassHz] wie beim Handy-Mikrofon (2. Ordnung),
     * [roomNoiseDb] Grundrauschen in dBFS (RMS) nach dem Normieren.
     */
    fun synth(
        segments: List<Segment>,
        rate: Int = 44_100,
        quality: Quality = MODAL,
        seed: Int = 1,
        micHighPassHz: Double = 100.0,
        peak: Double = 0.5,
        roomNoiseDb: Double = -70.0,
    ): Signal {
        val random = java.util.Random(seed.toLong())
        val total = segments.sumOf { samplesOf(it, rate) }
        val out = DoubleArray(total)

        // Glottis
        var cyclePos = 0.0
        var period = rate / 120.0
        var cycleAmp = 1.0
        var flowPrev = 0.0
        // Knarrstimme
        var fryPos = 0.0
        var fryPeriod = rate / 40.0
        var fryAmp = 1.0
        var fryStrong = true
        var fryFlowPrev = 0.0
        // Hüllkurven (einpolig geglättet, ca. 8 ms)
        val envCoef = exp(-1.0 / (0.008 * rate))
        var voiceEnv = 0.0
        var fryEnv = 0.0
        var whisperEnv = 0.0
        var fricEnv = 0.0
        // Vokaltrakt
        val firstVowel = segments.firstNotNullOfOrNull { vowelOf(it) } ?: A
        val formF = firstVowel.f.copyOf()
        val formBw = DoubleArray(4) { firstVowel.bw[it] * if (it == 0) quality.f1Damping else 1.0 }
        var target = firstVowel
        val a1 = DoubleArray(4)
        val a2 = DoubleArray(4)
        val gain = DoubleArray(4)
        val y1 = DoubleArray(4)
        val y2 = DoubleArray(4)
        val formCoef = exp(-UPDATE / (0.03 * rate))
        // Reibelaut: Hochpass 2. Ordnung bei 3,5 kHz
        val fric = Biquad.highPass(rate, 3500.0)
        val fricBuf = FloatArray(1)

        var pos = 0
        for (seg in segments) {
            val n = samplesOf(seg, rate)
            vowelOf(seg)?.let { target = it }
            val voiceTarget = if (seg is Voice) db(seg.levelDb) else 0.0
            val fryTarget = if (seg is Fry) db(seg.levelDb) else 0.0
            val whisperTarget = if (seg is Whisper) db(seg.levelDb) else 0.0
            val fricTarget = if (seg is Fricative) db(seg.levelDb) else 0.0
            for (i in 0 until n) {
                if ((pos + i) % UPDATE.toInt() == 0) {
                    for (k in 0 until 4) {
                        formF[k] = target.f[k] + (formF[k] - target.f[k]) * formCoef
                        val bw = target.bw[k] * if (k == 0) quality.f1Damping else 1.0
                        formBw[k] = bw + (formBw[k] - bw) * formCoef
                        val r = exp(-PI * formBw[k] / rate)
                        a1[k] = 2 * r * cos(2 * PI * formF[k] / rate)
                        a2[k] = -r * r
                        gain[k] = 1 - a1[k] - a2[k]
                    }
                }
                voiceEnv = voiceTarget + (voiceEnv - voiceTarget) * envCoef
                fryEnv = fryTarget + (fryEnv - fryTarget) * envCoef
                whisperEnv = whisperTarget + (whisperEnv - whisperTarget) * envCoef
                fricEnv = fricTarget + (fricEnv - fricTarget) * envCoef
                val t = i.toDouble() / rate

                var e = 0.0
                // Stimmlippen: Periode je Zyklus mit Jitter, Amplitude mit Shimmer
                cyclePos += 1.0
                if (cyclePos >= period) {
                    cyclePos -= period
                    val f0 = if (seg is Voice) seg.pitch(t) else rate / period
                    period = rate / f0 * (1 + quality.jitter * random.nextGaussian().coerceIn(-3.0, 3.0))
                    cycleAmp = 1 + quality.shimmer * random.nextGaussian().coerceIn(-3.0, 3.0)
                }
                val phase = cyclePos / period
                val flow = rosenberg(phase, quality.openQuotient)
                if (voiceEnv > 1e-5) {
                    val open = phase < quality.openQuotient
                    val excitation = (flow - flowPrev) * period * cycleAmp
                    val aspiration = quality.breath * 8 * (random.nextDouble() * 2 - 1) * (if (open) 1.0 else 0.3)
                    e += voiceEnv * (excitation + aspiration)
                }
                flowPrev = flow
                // Knarrstimme
                fryPos += 1.0
                if (fryPos >= fryPeriod) {
                    fryPos -= fryPeriod
                    val fryMean = if (seg is Fry) seg.meanHz else 40.0
                    fryPeriod = rate / fryMean * (0.7 + 0.6 * random.nextDouble())
                    fryStrong = !fryStrong
                    fryAmp = if (fryStrong) 1.0 else 0.5 + 0.3 * random.nextDouble()
                }
                val fryOq = min(0.6, 0.004 * rate / fryPeriod)
                val fryFlow = rosenberg(fryPos / fryPeriod, fryOq)
                if (fryEnv > 1e-5) e += fryEnv * (fryFlow - fryFlowPrev) * (0.008 * rate) * fryAmp
                fryFlowPrev = fryFlow
                if (whisperEnv > 1e-5) e += whisperEnv * 2.5 * (random.nextDouble() * 2 - 1)

                var x = e
                for (k in 0 until 4) {
                    val y = gain[k] * x + a1[k] * y1[k] + a2[k] * y2[k]
                    y2[k] = y1[k]
                    y1[k] = y
                    x = y
                }
                if (fricEnv > 1e-5) {
                    fricBuf[0] = (random.nextDouble() * 2 - 1).toFloat()
                    fric.process(fricBuf, 1)
                    x += fricEnv * 1.5 * fricBuf[0]
                }
                out[pos + i] = x
            }
            pos += n
        }

        // Mikrofon-Hochpass, Normieren, Grundrauschen
        val samples = FloatArray(total) { out[it].toFloat() }
        if (micHighPassHz > 0) Biquad.highPass(rate, micHighPassHz).process(samples, total)
        val maxAbs = samples.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1f
        val noise = 10.0.pow(roomNoiseDb / 20) * sqrt(3.0)
        val noiseRandom = java.util.Random(seed + 1000L)
        for (i in 0 until total) samples[i] = (samples[i] / maxAbs * peak + noise * (noiseRandom.nextDouble() * 2 - 1)).toFloat()
        return Signal(samples, rate, truth(segments, rate, total))
    }

    private const val UPDATE = 32.0

    /** Abschnitte in ganzen 10-ms-Rahmen, damit Wahrheit und Analyse-Rahmen über lange Folgen deckungsgleich bleiben. */
    private fun samplesOf(seg: Segment, rate: Int) = (seg.seconds * 100).roundToInt() * rate / 100

    private fun vowelOf(seg: Segment) = when (seg) {
        is Voice -> seg.vowel
        is Fry -> seg.vowel
        is Whisper -> seg.vowel
        else -> null
    }

    private fun db(level: Double) = 10.0.pow(level / 20)

    /** Glottisfluss (Rosenberg C): Öffnen 2/3, Schließen 1/3 der offenen Phase, dann geschlossen. */
    private fun rosenberg(phase: Double, openQuotient: Double): Double {
        val opening = openQuotient * 2 / 3
        return when {
            phase < opening -> 0.5 * (1 - cos(PI * phase / opening))
            phase < openQuotient -> cos(PI / 2 * (phase - opening) / (openQuotient - opening))
            else -> 0.0
        }
    }

    /**
     * Wahre F0 je Rahmen. Nach einer Pause zählt der Einsatz ab 10 ms; am Ende klingt die Stimme
     * mit der letzten Tonhöhe noch etwa 10 ms nach (Hüllkurve), das zählt mit.
     */
    private fun truth(segments: List<Segment>, rate: Int, total: Int): FloatArray {
        val frames = total * 100 / rate
        val truth = FloatArray(frames)
        var start = 0.0
        for ((index, seg) in segments.withIndex()) {
            val end = start + samplesOf(seg, rate).toDouble() / rate
            if (seg is Voice) {
                val connectedBefore = index > 0 && segments[index - 1] is Voice
                val connectedAfter = index + 1 < segments.size && segments[index + 1] is Voice
                val from = start + if (connectedBefore) 0.0 else 0.01
                val to = end + if (connectedAfter) 0.0 else 0.01
                for (k in 0 until frames) {
                    val t = k * 0.01 + 0.02
                    if (t >= from && t < to) truth[k] = seg.pitch(min(t - start, seg.seconds)).toFloat()
                }
            }
            start = end
        }
        return truth
    }

    /** Zusätzliches Rauschen mit [snrDb] Abstand zur mittleren Leistung der stimmhaften Rahmen. */
    fun withNoise(signal: Signal, snrDb: Double, pink: Boolean = true, seed: Int = 5): Signal {
        val hop = signal.rate / 100
        var power = 0.0
        var count = 0
        for (k in signal.truth.indices) {
            if (signal.truth[k] <= 0f) continue
            for (j in k * hop until min(signal.samples.size, k * hop + hop)) power += signal.samples[j].toDouble().pow(2)
            count += hop
        }
        val speechRms = sqrt(power / max(1, count))
        val random = java.util.Random(seed.toLong())
        val noise = if (pink) pinkNoise(signal.samples.size, seed) else DoubleArray(signal.samples.size) { random.nextGaussian() }
        val noiseRms = sqrt(noise.sumOf { it * it } / noise.size)
        val scale = speechRms * 10.0.pow(-snrDb / 20) / noiseRms
        val out = FloatArray(signal.samples.size) { (signal.samples[it] + noise[it] * scale).toFloat() }
        return Signal(out, signal.rate, signal.truth)
    }

    /** Rosa Rauschen (−3 dB/Oktave) nach Paul Kellet. */
    private fun pinkNoise(n: Int, seed: Int): DoubleArray {
        val random = java.util.Random(seed.toLong())
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        return DoubleArray(n) {
            val w = random.nextGaussian()
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val v = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            v
        }
    }

    /** Ganze Aufnahme lauter oder leiser; mit [clip] hart auf ±1 begrenzt wie ein übersteuerter Wandler. */
    fun withGain(signal: Signal, gainDb: Double, clip: Boolean = false): Signal {
        val g = 10.0.pow(gainDb / 20).toFloat()
        val out = FloatArray(signal.samples.size) {
            val v = signal.samples[it] * g
            if (clip) v.coerceIn(-1f, 1f) else v
        }
        return Signal(out, signal.rate, signal.truth)
    }

    /** Erwartete Noten aus der Wahrheit; mehrere nur, wo die Wahrheit wirklich auf der Kippe steht. */
    class Expected(val mostFrequent: Set<Int>, val lowest: Set<Int>, val highest: Set<Int>, val voicedMs: Long) {
        val hasVoice get() = mostFrequent.isNotEmpty()
        override fun toString() = "häufigste ${names(mostFrequent)}, tiefste ${names(lowest)}, höchste ${names(highest)}, ${voicedMs} ms"
    }

    fun names(notes: Collection<Int>) = notes.sorted().joinToString("/") { Notes.name(it) }

    /**
     * Wendet die Regeln der Statistik auf die wahre F0 an. Damit Rahmen, die genau auf einer
     * Notengrenze oder knapp an der Mindestdauer liegen, nicht über Erfolg entscheiden, gelten
     * mehrere Noten, wo die Wahrheit auf der Kippe steht:
     * - tiefste/höchste: Tonhöhe ±[shift] Halbtöne verschoben, Mindestdauer halb und doppelt so lang;
     * - häufigste: jede Note, die mit allen Rahmen bis [shift] an ihren Grenzen mindestens 90 % der
     *   Rahmen erreicht, die irgendeine andere Note sicher (abseits der Grenzen) hat.
     */
    fun expected(truth: FloatArray, shift: Double = 0.15): Expected {
        val pitches = mutableListOf<Double>()
        var i = 0
        while (i < truth.size) {
            if (truth[i] <= 0f) {
                i++
                continue
            }
            var end = i
            while (end < truth.size && truth[end] > 0f) end++
            if (end - i >= PitchStatistics.MIN_RUN_FRAMES) {
                for (k in i until end) {
                    if (truth[k] < PitchTracker.MIN_F0 || truth[k] > PitchTracker.MAX_F0) continue
                    pitches += Notes.semitones(truth[k].toDouble())
                }
            }
            i = end
        }
        val total = pitches.size
        if (total * 10L < PitchStatistics.MIN_VOICED_MS) return Expected(emptySet(), emptySet(), emptySet(), total * 10L)

        val sure = IntArray(128)
        val possible = IntArray(128)
        for (p in pitches) {
            val n = p.roundToInt()
            val frac = p - n
            possible[n]++
            if (abs(frac) < 0.5 - shift) sure[n]++ else possible[(n + if (frac > 0) 1 else -1).coerceIn(0, 127)]++
        }
        val best = sure.max()
        val most = possible.indices.filter { possible[it] > 0 && possible[it] >= 0.9 * best }.toMutableSet()

        val low = mutableSetOf<Int>()
        val high = mutableSetOf<Int>()
        for (s in doubleArrayOf(0.0, -shift, shift)) {
            val counts = IntArray(128)
            for (p in pitches) counts[(p + s).roundToInt().coerceIn(0, 127)]++
            for (factor in doubleArrayOf(0.5, 1.0, 2.0)) {
                val minFrames = ceil(max(PitchStatistics.MIN_NOTE_FRAMES.toDouble(), total * PitchStatistics.MIN_NOTE_SHARE) * factor)
                counts.indices.firstOrNull { counts[it] >= minFrames }?.let { low += it }
                counts.indices.lastOrNull { counts[it] >= minFrames }?.let { high += it }
            }
        }
        return Expected(most, low, high, total * 10L)
    }

    /** Prüft ein Ergebnis gegen die Erwartung; liefert eine Fehlermeldung oder null. */
    fun mismatch(result: PitchResult, expected: Expected): String? {
        val ok = if (!expected.hasVoice) !result.hasVoice else
            result.mostFrequent in expected.mostFrequent && result.lowest in expected.lowest && result.highest in expected.highest
        if (ok) return null
        val got = if (result.hasVoice) "häufigste ${Notes.name(result.mostFrequent!!)}, tiefste ${Notes.name(result.lowest!!)}, " +
            "höchste ${Notes.name(result.highest!!)}" else "keine Noten"
        return "erwartet $expected, erkannt $got, ${result.voicedMs} ms"
    }
}
