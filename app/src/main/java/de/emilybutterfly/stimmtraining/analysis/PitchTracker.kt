package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Bestimmt gestreamt den Grundton (F0) alle 10 ms nach YIN (de Cheveigné & Kawahara 2002):
 * Differenzfunktion (direkt berechnet), kumulativ normierte Differenz (CMND), erstes lokales
 * Minimum unter der absoluten Schwelle und parabolische Interpolation. Die Schwelle gilt für den
 * zwischen den Abtastpunkten interpolierten Tiefstwert, damit hohe Stimmen (wenige Werte pro
 * Periode) nicht übersprungen werden. Pro Rahmen werden nur F0 (0 = nicht periodisch) und Pegel
 * gespeichert, damit auch einstündige Aufnahmen wenig Speicher brauchen.
 *
 * Entscheidung je Rahmen:
 * 1. Stimmhaft und Periode: aus der Summe der Differenzfunktionen von ±[RADIUS] Nachbarrahmen
 *    (wie ein 70-ms-Fenster), damit Hauch und Rauschen weniger streuen. Erst die strenge Schwelle
 *    [THRESHOLD], ohne Treffer die weitere [BREATHY_THRESHOLD] für behauchte Stimmen, sonst
 *    Fortsetzung auf der Tonhöhe des vorigen Rahmens ([CONTINUE_THRESHOLD]).
 * 2. Liegt das erste Minimum bei einem Vielfachen, weil die Periode selbst knapp über der Schwelle
 *    blieb, gilt die kürzere, fast gleich gute Periode.
 * 3. Kein Ton: über dem Suchbereich (Pfeifen) oder ohne gleichbleibende Periodizität
 *    (nachklingende Formanten, schmalbandiges Rauschen, siehe [isSustained]).
 * 4. Oktavprüfung im eigenen Rahmen: ist das Zwei- bis Vierfache der Periode deutlich
 *    periodischer (schwacher oder fehlender Grundton, starker 2. Teilton), gilt das Vielfache.
 *    Fehlentscheidungen bleiben so vereinzelt, und die Statistik faltet sie zurück.
 *
 * Die Entscheidung für einen Rahmen fällt erst, wenn seine Nachbarn da sind; [finish] wertet am
 * Ende die letzten Rahmen aus.
 */
internal class PitchTracker(val sampleRate: Int) {
    val hopSize = sampleRate * HOP_MS / 1000
    val hopMs = HOP_MS
    /** Fensterlänge, durch 4 teilbar für die entrollte Schleife (256 bei 8 kHz). */
    private val window = sampleRate * WINDOW_MS / 1000 / 4 * 4
    private val tauMin = max(2, floor(sampleRate / MAX_F0).toInt())
    private val tauMax = ceil(sampleRate / MIN_F0).toInt()
    private val highTau = floor(sampleRate / HIGH_F0).toInt()

    /** Werte pro Rahmen: Fenster plus größte Verschiebung plus ein Wert für die Interpolation. */
    private val span = window + tauMax + 1
    private val buffer = FloatArray(span + 4096)
    private var filled = 0
    private var next = 0

    /** Differenzfunktionen der letzten 2·[RADIUS]+1 Rahmen (Ringpuffer; leise Rahmen bleiben 0). */
    private val recent = Array(2 * RADIUS + 1) { FloatArray(tauMax + 2) }
    private var computed = 0

    private val diff = FloatArray(tauMax + 2)
    private val cmnd = FloatArray(tauMax + 2)
    private val ownCmnd = FloatArray(tauMax + 2)

    /** F0 je Rahmen in Hz, 0 wenn nicht periodisch oder zu leise. */
    val f0 = FloatList()

    /** Mittlere Leistung je Rahmen in dBFS (Vollaussteuerungs-Sinus = -3 dB). */
    val energyDb = FloatList()

    /** Anzahl ausgewerteter Rahmen (nach [finish] alle). */
    val frameCount: Int get() = f0.size

    fun feed(samples: FloatArray, count: Int) {
        var pos = 0
        while (pos < count) {
            if (filled == buffer.size) {
                // Ausgewertete Werte verwerfen; übrig bleibt weniger als ein Rahmen.
                val keep = filled - next
                System.arraycopy(buffer, next, buffer, 0, keep)
                filled = keep
                next = 0
            }
            val n = min(count - pos, buffer.size - filled)
            System.arraycopy(samples, pos, buffer, filled, n)
            filled += n
            pos += n
            while (next + span <= filled) {
                analyzeFrame(next)
                next += hopSize
            }
        }
    }

    /** Wertet die noch zurückgehaltenen letzten Rahmen aus (ohne Nachfolger). */
    fun finish() {
        while (f0.size < computed) decide(f0.size)
    }

    private fun analyzeFrame(start: Int) {
        val x = buffer
        var sum = 0.0
        for (j in start until start + window) {
            val v = x[j]
            sum += v * v
        }
        val db = (10 * log10(sum / window + 1e-12)).toFloat()
        energyDb.add(db)
        val d = recent[computed % recent.size]
        if (db < ENERGY_FLOOR_DB) d.fill(0f) else difference(start, d)
        computed++
        if (computed > RADIUS) decide(computed - 1 - RADIUS)
    }

    /** Differenzfunktion für Verschiebungen 1..tauMax+1. */
    private fun difference(start: Int, d: FloatArray) {
        val x = buffer
        val end = start + window
        d[0] = 0f
        for (tau in 1..tauMax + 1) {
            // Vier unabhängige Teilsummen: die Additionen warten nicht aufeinander (spürbar schneller auf ARM).
            var acc0 = 0f
            var acc1 = 0f
            var acc2 = 0f
            var acc3 = 0f
            var j = start
            var k = start + tau
            while (j < end) {
                val d0 = x[j] - x[k]
                val d1 = x[j + 1] - x[k + 1]
                val d2 = x[j + 2] - x[k + 2]
                val d3 = x[j + 3] - x[k + 3]
                acc0 += d0 * d0
                acc1 += d1 * d1
                acc2 += d2 * d2
                acc3 += d3 * d3
                j += 4
                k += 4
            }
            d[tau] = (acc0 + acc1) + (acc2 + acc3)
        }
    }

    private fun decide(frame: Int) {
        if (energyDb[frame] < ENERGY_FLOOR_DB) {
            f0.add(0f)
            return
        }
        diff.fill(0f)
        for (j in max(0, frame - RADIUS)..min(computed - 1, frame + RADIUS)) {
            val d = recent[j % recent.size]
            for (t in diff.indices) diff[t] += d[t]
        }
        normalize(diff, cmnd)
        f0.add(estimate(recent[frame % recent.size]))
    }

    /** Kumulativ normierte Differenz (CMND) aus [d]. */
    private fun normalize(d: FloatArray, out: FloatArray) {
        var running = 0.0
        out[0] = 1f
        for (tau in 1..tauMax + 1) {
            running += d[tau]
            out[tau] = if (running > 0.0) (d[tau] * tau / running).toFloat() else 1f
        }
    }

    /** YIN für einen Rahmen; 0, wenn keine ausreichend periodische Stelle im Suchbereich liegt. */
    private fun estimate(own: FloatArray): Float {
        normalize(own, ownCmnd)
        // Erstes lokales Minimum, dessen Tiefstwert (zwischen den Abtastpunkten interpoliert)
        // unter der Schwelle liegt; erst wenn keines darunter liegt, gilt die weitere Schwelle für
        // behauchte Stimmen. Fällt die Kurve am oberen Rand noch, liegt der Grundton unterhalb des
        // Suchbereichs und es gibt dort kein Minimum.
        var tau = firstMinimumBelow(THRESHOLD)
        if (tau > tauMax) tau = firstMinimumBelow(BREATHY_THRESHOLD)
        if (tau > tauMax) return continuePrevious(own)
        tau = preferShorter(tau)
        if (tau < tauMin || isAboveRange(tau) || !isSustained(tau)) return 0f

        // Oktavprüfung und Frequenz im eigenen Rahmen
        var m = -1
        for (t in max(2, tau - 1)..min(tauMax, tau + 1)) {
            if (isMinimum(ownCmnd, t) && (m < 0 || ownCmnd[t] < ownCmnd[m])) m = t
        }
        val final = if (m < 0) tau else checkOctave(m)
        val d = if (m < 0) diff else own
        var best = final
        for (t in max(2, final - 1)..min(tauMax, final + 1)) if (d[t] < d[best]) best = t
        return (sampleRate / interpolate(d, best)).toFloat()
    }

    /**
     * Setzt einen stimmhaften Abschnitt fort: Liegt nahe der Periode des vorigen Rahmens ein
     * Minimum unter [CONTINUE_THRESHOLD], gilt es. Sehr behauchte tiefe Stimmen pendeln sonst um
     * die Schwelle und zerfallen in Stücke, die zu kurz zum Zählen sind.
     */
    private fun continuePrevious(own: FloatArray): Float {
        val previous = if (f0.size > 0) f0[f0.size - 1] else 0f
        if (previous <= 0f) return 0f
        val period = sampleRate / previous
        var best = -1
        for (t in max(tauMin, floor(period * 0.95f).toInt())..min(tauMax, ceil(period * 1.05f).toInt())) {
            if (isMinimum(cmnd, t) && minimumValue(cmnd, t) < CONTINUE_THRESHOLD && (best < 0 || cmnd[t] < cmnd[best])) best = t
        }
        if (best < 0 || !isSustained(best)) return 0f
        val d = if (isMinimum(own, best)) own else diff
        return (sampleRate / interpolate(d, best)).toFloat()
    }

    /**
     * Echte Stimme schwingt über das ganze Fenster weiter: Bei der doppelten Periode ist die CMND
     * des Rahmens kaum schlechter. Nachklingende Formanten (zwischen den Pulsen der Knarrstimme,
     * am Ende eines Lauts) und schmalbandiges Rauschen (Flüstern) verlieren dagegen von Periode zu
     * Periode an Ähnlichkeit; sie gelten nicht als Stimme, statt als Ton auf der Formantfrequenz.
     * Gesucht wird ±5 % um die doppelte Periode, damit Vibrato und Gleiten nicht stören.
     */
    private fun isSustained(tau: Int): Boolean {
        val c = ownCmnd
        val reach = max(2, tau / 10)
        if (2 * tau + reach > tauMax) return true
        var base = c[tau]
        for (t in max(2, tau - 1)..tau + 1) if (isMinimum(c, t)) base = min(base, minimumValue(c, t))
        var double = Float.MAX_VALUE
        for (t in 2 * tau - reach..2 * tau + reach) if (isMinimum(c, t)) double = min(double, minimumValue(c, t))
        return double < base + DECAY_LIMIT
    }

    private fun firstMinimumBelow(threshold: Float): Int {
        var tau = tauMin
        while (tau <= tauMax && !(isMinimum(cmnd, tau) && minimumValue(cmnd, tau) < threshold)) tau++
        return tau
    }

    private fun isMinimum(c: FloatArray, tau: Int) = c[tau] <= c[tau - 1] && c[tau] < c[tau + 1]

    /** Tiefster Wert der CMND um [tau], per Parabel durch die drei Nachbarpunkte. */
    private fun minimumValue(c: FloatArray, tau: Int): Float {
        val a = c[tau - 1]
        val b = c[tau]
        val cc = c[tau + 1]
        val denom = a + cc - 2 * b
        if (denom <= 0f) return b
        return max(0f, b - (a - cc) * (a - cc) / (8 * denom))
    }

    /**
     * Liegt die gefundene Periode nur knapp unter der Schwelle, kann ein Drittel oder die Hälfte
     * davon fast genauso periodisch sein: Bei behauchter Stimme schwanken die Werte so stark, dass
     * zufällig erst ein Vielfaches unter die Schwelle fällt. Dann gilt die kürzere Periode.
     */
    private fun preferShorter(tau: Int): Int {
        val value = minimumValue(cmnd, tau)
        for (factor in 3 downTo 2) {
            val center = tau.toDouble() / factor
            var found = -1
            for (t in max(2, floor(center).toInt() - 1)..min(tauMax, ceil(center).toInt() + 1)) {
                if (!isMinimum(cmnd, t)) continue
                val v = minimumValue(cmnd, t)
                if (v <= NEAR_FACTOR * value && v < NEAR_LIMIT) found = t
            }
            if (found > 0) return found
        }
        return tau
    }

    /**
     * Ist schon ein Bruchteil der Periode (unterhalb des Suchbereichs, also über [MAX_F0]) ebenso
     * periodisch, ist der Ton höher als gesucht, etwa ein Pfeifen; sonst würde ein Unterton gemeldet.
     * Ist die volle Periode dagegen deutlich periodischer (starker 2. Teilton), gilt sie.
     */
    private fun isAboveRange(tau: Int): Boolean {
        val value = minimumValue(cmnd, tau)
        for (factor in 2..4) {
            val center = tau.toDouble() / factor
            if (center >= tauMin) continue
            for (t in max(2, floor(center).toInt() - 1)..min(tauMin, ceil(center).toInt() + 1)) {
                if (!isMinimum(cmnd, t)) continue
                val short = minimumValue(cmnd, t)
                val clearlyMorePeriodic = value < OCTAVE_RATIO * short && short - value > ABOVE_RANGE_MARGIN
                if (short < THRESHOLD && !clearlyMorePeriodic) return true
            }
        }
        return false
    }

    /**
     * Nimmt schrittweise das kleinste Vielfache (2 bis 4) der Periode, das deutlich periodischer
     * ist (schwacher oder fehlender Grundton, starker 2. oder 3. Teilton). Die strenge Schwelle
     * verhindert, dass Unregelmäßigkeiten der Stimme (Jitter) zu Sprüngen nach unten führen. Der
     * Mindestabstand wächst, wenn die Parabel den Tiefstwert stark absenkt (schmales Minimum
     * zwischen zwei Abtastpunkten, ungenauer Wert), etwa bei Pulsfolgen.
     */
    private fun checkOctave(start: Int): Int {
        var tau = start
        var changed = true
        while (changed) {
            changed = false
            val base = minimumValue(ownCmnd, tau)
            val margin = max(OCTAVE_MARGIN, ownCmnd[tau] - base)
            for (factor in 2..MAX_FACTOR) {
                val center = tau * factor
                val lo = max(tauMin + 1, center - factor)
                val hi = min(tauMax, center + factor)
                if (lo > hi) break
                var m = -1
                var v = 0f
                for (t in lo..hi) {
                    if (!isMinimum(ownCmnd, t)) continue
                    val value = minimumValue(ownCmnd, t)
                    if (m < 0 || value < v) {
                        m = t
                        v = value
                    }
                }
                // Ganz oben im Suchbereich ist der 2. Teilton einer hohen Stimme wahrscheinlicher als ein Grundton
                val ratio = if (factor == 2 && tau < highTau) HIGH_OCTAVE_RATIO else OCTAVE_RATIO
                if (m >= 0 && v < ratio * base && base - v > margin) {
                    tau = m
                    changed = true
                    break
                }
            }
        }
        return tau
    }

    /** Parabolische Interpolation auf der rohen Differenzfunktion (ohne Verzerrung durch die Normierung). */
    private fun interpolate(d: FloatArray, tau: Int): Double {
        val a = d[tau - 1].toDouble()
        val b = d[tau].toDouble()
        val c = d[tau + 1].toDouble()
        val denom = a + c - 2 * b
        if (denom <= 1e-12) return tau.toDouble()
        return tau + (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
    }

    companion object {
        const val HOP_MS = 10
        const val WINDOW_MS = 32
        const val MIN_F0 = 60.0
        const val MAX_F0 = 1000.0

        /** Nachbarrahmen je Seite, deren Differenzfunktionen für die Entscheidung addiert werden. */
        const val RADIUS = 2

        /** Absolute YIN-Schwelle für die (über die Nachbarrahmen geglättete) CMND. */
        const val THRESHOLD = 0.2f

        /**
         * Weitere Schwelle, wenn kein Minimum unter [THRESHOLD] liegt: Hauch und Jitter heben die
         * CMND behauchter Stimmen über 0,2; Rauschen und Flüstern bleiben darüber.
         */
        const val BREATHY_THRESHOLD = 0.3f

        /** Schwelle, um einen stimmhaften Abschnitt auf der vorigen Tonhöhe fortzusetzen. */
        const val CONTINUE_THRESHOLD = 0.4f

        /** Absolute Untergrenze für Stimme; leiser ist Stille oder Grundrauschen. */
        const val ENERGY_FLOOR_DB = -60f

        /** Kürzere Periode gilt, wenn ihr Wert höchstens so viel mal größer und unter [NEAR_LIMIT] ist. */
        private const val NEAR_FACTOR = 2f
        private const val NEAR_LIMIT = 0.4f

        /** So viel schlechter darf die CMND bei der doppelten Periode höchstens sein ([isSustained]). */
        private const val DECAY_LIMIT = 0.2f

        /** Vielfaches gilt, wenn sein Wert unter diesem Anteil liegt und um [OCTAVE_MARGIN] kleiner ist. */
        private const val OCTAVE_RATIO = 0.25f
        private const val OCTAVE_MARGIN = 0.005f
        private const val MAX_FACTOR = 4

        /** Über [HIGH_F0] genügt für die tiefere Oktave, dass sie halb so aperiodisch ist (2. Teilton wahrscheinlicher). */
        private const val HIGH_F0 = 700.0
        private const val HIGH_OCTAVE_RATIO = 0.5f

        /** Mindestabstand, damit die volle Periode trotz periodischem Bruchteil (Pfeifen) gilt. */
        private const val ABOVE_RANGE_MARGIN = 0.05f
    }
}

/** Wachsende Float-Liste ohne Boxing. */
internal class FloatList(capacity: Int = 1024) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Float = data[index]

    fun toArray(): FloatArray = data.copyOf(size)
}
