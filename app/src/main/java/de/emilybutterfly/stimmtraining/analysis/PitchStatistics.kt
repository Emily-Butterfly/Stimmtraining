package de.emilybutterfly.stimmtraining.analysis

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wertet die F0-Werte pro Rahmen aus:
 * 1. Pegeltor: Rahmen mehr als [RELATIVE_GATE_DB] unter dem 95. Perzentil der Rahmenpegel
 *    (und unter der absoluten Untergrenze) zählen als Stille oder Störgeräusch.
 * 2. Zusammenhängende Stimmabschnitte (Lücken bis [BRIDGE_FRAMES] Rahmen überbrückt) unter
 *    [MIN_RUN_FRAMES] Rahmen werden verworfen.
 * 3. Innerhalb der Abschnitte werden Oktavfehler zurückgefaltet (eine oder zwei Oktaven oder eine
 *    Duodezime daneben): zuerst kurze Stücke zwischen zwei Sprüngen (die Stimme gleitet, springt
 *    aber nicht für 50 ms um eine Oktave), dann einzelne Werte, jeweils gegenüber dem Median der
 *    umgebenden ±[CONTEXT_FRAMES] Rahmen. Danach glättet ein Median über [MEDIAN_FRAMES] Rahmen
 *    einzelne Ausreißer. Echte Sprünge, die gehalten werden, verschieben den Umgebungsmedian mit
 *    und bleiben erhalten.
 * 4. Häufigste Note nach Rahmenzahl; tiefste und höchste nur aus Noten, die zusammen lange genug
 *    vorkommen, damit einzelne Ausreißer nicht den Umfang bestimmen.
 */
internal object PitchStatistics {
    const val RELATIVE_GATE_DB = 30f
    const val MIN_RUN_FRAMES = 6
    const val MEDIAN_FRAMES = 5
    const val MIN_VOICED_MS = 500L
    const val MIN_NOTE_FRAMES = 5
    const val MIN_NOTE_SHARE = 0.005
    const val CONTEXT_FRAMES = 20

    /** So viele unstimmhafte Rahmen in Folge unterbrechen einen Stimmabschnitt noch nicht. */
    const val BRIDGE_FRAMES = 3
    private const val OCTAVE = 12f
    private const val TWELFTH = 19.02f // Faktor 3
    private const val TWO_OCTAVES = 24f // Faktor 4
    private const val FOLD_TOLERANCE = 2.5f

    /** Größere Sprünge zwischen zwei Rahmen trennen Stücke (Halbtöne). */
    private const val JUMP = 5f

    /** Längstes Stück zwischen zwei Sprüngen, das als Ganzes gefaltet werden kann. */
    private const val MAX_FOLD_PIECE = 25

    fun compute(f0: FloatArray, energyDb: FloatArray, count: Int, hopMs: Int): PitchResult {
        if (count == 0) return PitchResult(null, null, null, 0)

        val sortedEnergy = energyDb.copyOf(count).also { it.sort() }
        val p95 = sortedEnergy[((count - 1) * 0.95).roundToInt()]
        val gate = max(PitchTracker.ENERGY_FLOOR_DB, p95 - RELATIVE_GATE_DB)

        val counts = IntArray(128)
        val pitches = FloatList()
        var i = 0
        while (i < count) {
            if (!isVoiced(f0[i], energyDb[i], gate)) {
                i++
                continue
            }
            // Stimmabschnitt; Lücken bis [BRIDGE_FRAMES] Rahmen (ein unsicherer Rahmen mitten im Laut) gehören dazu
            val run = FloatList(64)
            var gap = 0
            while (i < count) {
                if (isVoiced(f0[i], energyDb[i], gate)) {
                    run.add(f0[i])
                    gap = 0
                } else if (++gap > BRIDGE_FRAMES) {
                    break
                }
                i++
            }
            if (run.size >= MIN_RUN_FRAMES) {
                for (semitones in smoothRun(run.toArray())) {
                    pitches.add(semitones)
                    counts[semitones.roundToInt().coerceIn(0, 127)]++
                }
            }
        }

        val total = pitches.size
        val voicedMs = total.toLong() * hopMs
        if (voicedMs < MIN_VOICED_MS) return PitchResult(null, null, null, voicedMs)

        val sortedPitches = pitches.toArray().also { it.sort() }
        val median = sortedPitches[total / 2]
        var mostFrequent = 0
        for (note in 1 until counts.size) {
            val better = counts[note] > counts[mostFrequent] ||
                (counts[note] == counts[mostFrequent] && abs(note - median) < abs(mostFrequent - median))
            if (better) mostFrequent = note
        }

        val minFrames = max(MIN_NOTE_FRAMES, ceil(total * MIN_NOTE_SHARE).toInt())
        val lowest = counts.indices.firstOrNull { counts[it] >= minFrames } ?: mostFrequent
        val highest = counts.indices.lastOrNull { counts[it] >= minFrames } ?: mostFrequent
        return PitchResult(
            mostFrequent = mostFrequent,
            lowest = minOf(lowest, mostFrequent),
            highest = maxOf(highest, mostFrequent),
            voicedMs = voicedMs,
        )
    }

    private fun isVoiced(f0: Float, energyDb: Float, gate: Float) = f0 > 0f && energyDb >= gate

    /** Tonhöhen eines Stimmabschnitts (F0 in Hz) in Halbtönen: Oktavfehler gefaltet, dann Median. */
    private fun smoothRun(f0: FloatArray): FloatArray {
        val n = f0.size
        val raw = FloatArray(n) { Notes.semitones(f0[it].toDouble()).toFloat() }
        foldPieces(raw)
        val scratch = FloatArray(2 * CONTEXT_FRAMES + 1)
        val folded = FloatArray(n)
        for (k in 0 until n) {
            val from = max(0, k - CONTEXT_FRAMES)
            val to = min(n, k + CONTEXT_FRAMES + 1)
            System.arraycopy(raw, from, scratch, 0, to - from)
            scratch.sort(0, to - from)
            folded[k] = raw[k] - foldAmount(raw[k] - scratch[(to - from) / 2])
        }
        // Medianfenster bleibt im Abschnitt; an den Rändern wird es nach innen verschoben.
        val window = FloatArray(MEDIAN_FRAMES)
        return FloatArray(n) { k ->
            val from = (k - MEDIAN_FRAMES / 2).coerceIn(0, n - MEDIAN_FRAMES)
            System.arraycopy(folded, from, window, 0, MEDIAN_FRAMES)
            window.sort()
            window[MEDIAN_FRAMES / 2]
        }
    }

    /** Faltbetrag für eine Abweichung, die einem Oktavfehler entspricht, sonst 0. */
    private fun foldAmount(deviation: Float): Float = when {
        abs(deviation - OCTAVE) <= FOLD_TOLERANCE -> OCTAVE
        abs(deviation + OCTAVE) <= FOLD_TOLERANCE -> -OCTAVE
        abs(deviation - TWELFTH) <= FOLD_TOLERANCE -> TWELFTH
        abs(deviation + TWELFTH) <= FOLD_TOLERANCE -> -TWELFTH
        abs(deviation - TWO_OCTAVES) <= FOLD_TOLERANCE -> TWO_OCTAVES
        abs(deviation + TWO_OCTAVES) <= FOLD_TOLERANCE -> -TWO_OCTAVES
        else -> 0f
    }

    /**
     * Teilt den Abschnitt an Sprüngen über [JUMP] Halbtöne in Stücke und faltet kurze Stücke
     * (höchstens [MAX_FOLD_PIECE] Rahmen), deren Median gegenüber dem Median der umgebenden
     * ±[CONTEXT_FRAMES] Rahmen ohne das Stück selbst um eine Oktave (Duodezime, zwei Oktaven)
     * versetzt ist. Solche Fehler kommen in Gruppen, etwa am Silbenanfang; Rahmen für Rahmen
     * verschieben sie den Umgebungsmedian mit und würden nicht erkannt. Ändert [pitch] an Ort und Stelle.
     */
    private fun foldPieces(pitch: FloatArray) {
        val n = pitch.size
        val starts = mutableListOf(0)
        for (k in 1 until n) if (abs(pitch[k] - pitch[k - 1]) > JUMP) starts += k
        if (starts.size < 2) return
        starts += n
        val original = pitch.copyOf()
        val context = FloatArray(2 * CONTEXT_FRAMES)
        for (p in 0 until starts.size - 1) {
            val from = starts[p]
            val to = starts[p + 1]
            if (to - from > MAX_FOLD_PIECE) continue
            var count = 0
            for (k in max(0, from - CONTEXT_FRAMES) until from) context[count++] = original[k]
            for (k in to until min(n, to + CONTEXT_FRAMES)) context[count++] = original[k]
            if (count < to - from) continue
            context.sort(0, count)
            val amount = foldAmount(median(original, from, to) - context[count / 2])
            if (amount != 0f) for (k in from until to) pitch[k] -= amount
        }
    }

    private fun median(values: FloatArray, from: Int, to: Int): Float {
        val copy = values.copyOfRange(from, to)
        copy.sort()
        return copy[copy.size / 2]
    }
}
