package de.emilybutterfly.stimmtraining.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Statistik direkt auf F0-Verläufen (ein Wert je 10 ms), ohne Signalverarbeitung. */
class PitchStatisticsTest {
    private val f0 = mutableListOf<Float>()
    private val energy = mutableListOf<Float>()

    private fun frames(count: Int, midi: Int?, db: Float = -12f) {
        repeat(count) {
            f0 += midi?.let { Notes.frequency(it).toFloat() } ?: 0f
            energy += db
        }
    }

    private fun compute() = PitchStatistics.compute(f0.toFloatArray(), energy.toFloatArray(), f0.size, 10)

    @Test
    fun isolatedOctaveGlitchesAreSmoothedAway() {
        // Drei Stellen mit je zwei Rahmen auf der Oktave: zusammen 6 Rahmen, ohne Median also genug für „höchste“
        frames(100, 57)
        frames(2, 69)
        frames(100, 57)
        frames(2, 69)
        frames(100, 57)
        frames(2, 69)
        frames(100, 57)
        val result = compute()
        assertEquals(57, result.mostFrequent)
        assertEquals(57, result.lowest)
        assertEquals(57, result.highest)
    }

    @Test
    fun shortOctaveErrorsAreFoldedBack() {
        // Je 6 Rahmen eine Oktave zu tief (wie bei rauer Stimme): zu lang für den Median, zusammen 18 Rahmen
        repeat(3) {
            frames(100, 57)
            frames(6, 45)
        }
        frames(100, 57)
        val result = compute()
        assertEquals(57, result.lowest)
        assertEquals(57, result.highest)
        assertEquals(4180L, result.voicedMs)
    }

    @Test
    fun shortTwelfthErrorsAreFoldedBack() {
        frames(100, 52)
        frames(7, 33) // Faktor 3 zu tief (52 − 19)
        frames(100, 52)
        assertEquals(52, compute().lowest)
    }

    @Test
    fun octaveErrorGroupAtSyllableStartIsFolded() {
        // Silbe gleitet von C4 nach G#3; die ersten 6 Rahmen eine Oktave zu hoch (starker 2. Teilton beim Einsatz)
        val glide = List(40) { 60f - 4f * it / 39 }
        glide.forEachIndexed { i, semis ->
            f0 += (440.0 * Math.pow(2.0, (semis + (if (i < 6) 12 else 0) - 69) / 12.0)).toFloat()
            energy += -12f
        }
        frames(60, 56)
        val result = compute()
        assertEquals(60, result.highest)
        assertEquals(56, result.lowest)
    }

    @Test
    fun singleUnvoicedFrameDoesNotSplitTheSyllable() {
        // 3 richtige und 3 um eine Oktave zu hohe Rahmen, dann ein unsicherer Rahmen, dann der Rest der Silbe
        frames(3, 57)
        frames(3, 69)
        frames(1, null)
        frames(80, 57)
        val result = compute()
        assertEquals(57, result.highest)
        assertEquals(860L, result.voicedMs)
    }

    @Test
    fun twoOctaveErrorsAreFoldedBack() {
        frames(100, 45)
        frames(7, 69) // Faktor 4 zu hoch (fehlender Grundton, nur der 4. Teilton erkannt)
        frames(100, 45)
        assertEquals(45, compute().highest)
    }

    @Test
    fun shortLeapThatIsNoOctaveIsKept() {
        // Quinte für 150 ms mitten im Abschnitt: kein Oktavfehler, bleibt erhalten
        frames(100, 57)
        frames(15, 64)
        frames(100, 57)
        assertEquals(64, compute().highest)
    }

    @Test
    fun heldOctaveLeapIsKept() {
        // Ein echter Oktavsprung im selben Atemzug, gehalten
        frames(300, 57)
        frames(300, 69)
        val result = compute()
        assertEquals(57, result.lowest)
        assertEquals(69, result.highest)
    }

    @Test
    fun fastSirenOverAnOctaveIsKept() {
        // Eine Oktave in 0,65 s hinauf und wieder hinunter, ohne Pause (je Halbton 50 ms)
        for (note in 57..69) frames(5, note)
        for (note in 68 downTo 57) frames(5, note)
        frames(100, 57)
        val result = compute()
        assertEquals(57, result.lowest)
        assertEquals(69, result.highest)
    }

    @Test
    fun rareNotesDoNotDefineTheRange() {
        // D4 kommt nur 4 Rahmen lang mitten im A3 vor, F#3 dagegen 300 ms lang
        frames(500, 57)
        frames(4, 62)
        frames(500, 57)
        frames(20, null)
        frames(30, 54)
        val result = compute()
        assertEquals(57, result.mostFrequent)
        assertEquals(54, result.lowest)
        assertEquals(57, result.highest)
    }

    @Test
    fun rangeNeedsHalfAPercentInLongRecordings() {
        frames(4000, 57)
        frames(10, null)
        frames(15, 64) // 15 Rahmen < 0,5 % von 4040 Rahmen
        frames(10, null)
        frames(25, 50) // 25 Rahmen ≥ 0,5 %
        val result = compute()
        assertEquals(50, result.lowest)
        assertEquals(57, result.highest)
    }

    @Test
    fun shortVoicedRunsAreDropped() {
        frames(200, 57)
        repeat(10) {
            frames(10, null)
            frames(5, 64) // 50 ms: zu kurz
        }
        val result = compute()
        assertEquals(57, result.highest)
        assertEquals(2000L, result.voicedMs)
    }

    @Test
    fun framesFarBelowTheLoudPartsAreIgnored() {
        frames(300, 57, db = -10f)
        frames(300, 60, db = -35f) // 25 dB leiser: zählt
        frames(300, 64, db = -45f) // 35 dB leiser: Störgeräusch
        frames(300, 48, db = -65f) // unter der absoluten Untergrenze
        val result = compute()
        assertEquals(57, result.lowest)
        assertEquals(60, result.highest)
        assertEquals(6000L, result.voicedMs)
    }

    @Test
    fun absoluteFloorAppliesEvenToQuietRecordings() {
        frames(300, 57, db = -62f)
        val result = compute()
        assertFalse(result.hasVoice)
        assertEquals(0L, result.voicedMs)
    }

    @Test
    fun tieGoesToTheNoteCloserToTheMedian() {
        frames(100, 50)
        frames(150, 55)
        frames(150, 57)
        frames(100, 52)
        // 55 und 57 gleich häufig; Median liegt bei 55
        assertEquals(55, compute().mostFrequent)
    }

    @Test
    fun lessThanHalfASecondHasNoNotes() {
        frames(45, 57)
        val result = compute()
        assertNull(result.mostFrequent)
        assertNull(result.lowest)
        assertNull(result.highest)
        assertEquals(450L, result.voicedMs)
    }

    @Test
    fun spreadOutGlideStillHasConsistentRange() {
        // Jede Note nur 4 Rahmen: keine erreicht die Mindestdauer, Umfang fällt auf die häufigste zurück
        for (midi in 50..64) frames(4, midi)
        val result = compute()
        val most = result.mostFrequent!!
        assertEquals(most, result.lowest)
        assertEquals(most, result.highest)
    }

    @Test
    fun emptyInput() {
        val result = PitchStatistics.compute(FloatArray(0), FloatArray(0), 0, 10)
        assertFalse(result.hasVoice)
        assertEquals(0L, result.voicedMs)
    }
}
