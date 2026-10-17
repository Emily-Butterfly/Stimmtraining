package de.emilybutterfly.stimmtraining.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotesTest {
    @Test
    fun namesUseSharpsAndScientificOctaves() {
        assertEquals("A3", Notes.name(57))
        assertEquals("F#3", Notes.name(54))
        assertEquals("C4", Notes.name(60))
        assertEquals("A4", Notes.name(69))
        assertEquals("E3", Notes.name(52))
        assertEquals("D#3", Notes.name(51))
        assertEquals("B3", Notes.name(59))
        assertEquals("G3", Notes.name(55))
    }

    @Test
    fun edgeValuesAreNamed() {
        assertEquals("C-1", Notes.name(0))
        assertEquals("B-2", Notes.name(-1))
        assertEquals("C-2", Notes.name(-12))
        assertEquals("B-1", Notes.name(11))
        assertEquals("G9", Notes.name(127))
        assertEquals("A0", Notes.name(21))
        // Extreme Werte dürfen nicht abstürzen
        Notes.name(Int.MIN_VALUE)
        Notes.name(Int.MAX_VALUE)
    }

    @Test
    fun frequenciesAreEqualTempered() {
        assertEquals(440.0, Notes.frequency(69), 1e-9)
        assertEquals(220.0, Notes.frequency(57), 1e-9)
        assertEquals(261.6256, Notes.frequency(60), 1e-4)
        assertEquals(27.5, Notes.frequency(21), 1e-9)
    }

    @Test
    fun nearestNoteToAFrequency() {
        assertEquals(69, Notes.fromFrequency(440.0))
        assertEquals(60, Notes.fromFrequency(261.63))
        assertEquals(57, Notes.fromFrequency(220.0))
        assertEquals(54, Notes.fromFrequency(185.0))
        // Knapp unter und über der Mitte zwischen A4 und A#4 (452,89 Hz)
        assertEquals(69, Notes.fromFrequency(452.0))
        assertEquals(70, Notes.fromFrequency(454.0))
        for (midi in 0..127) assertEquals(midi, Notes.fromFrequency(Notes.frequency(midi)))
    }

    @Test
    fun invalidFrequenciesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Notes.fromFrequency(0.0) }
        assertThrows(IllegalArgumentException::class.java) { Notes.fromFrequency(-220.0) }
        assertThrows(IllegalArgumentException::class.java) { Notes.fromFrequency(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Notes.fromFrequency(Double.POSITIVE_INFINITY) }
    }
}
