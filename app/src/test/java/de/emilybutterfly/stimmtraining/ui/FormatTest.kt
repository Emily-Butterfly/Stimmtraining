package de.emilybutterfly.stimmtraining.ui

import de.emilybutterfly.stimmtraining.ui.components.formatDay
import de.emilybutterfly.stimmtraining.ui.components.recordingCount
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    @Test
    fun oneRecordingIsSingular() {
        assertEquals("1 Aufnahme", recordingCount(1))
        assertEquals("0 Aufnahmen", recordingCount(0))
        assertEquals("2 Aufnahmen", recordingCount(2))
    }

    @Test
    fun dayHeadersFollowTheGivenToday() {
        val day = LocalDate.of(2026, 11, 6)
        assertEquals("Heute", formatDay(day, today = day))
        assertEquals("Gestern", formatDay(day, today = day.plusDays(1)))
        assertEquals("Freitag, 6. November 2026", formatDay(day, today = day.plusDays(2)))
    }
}
