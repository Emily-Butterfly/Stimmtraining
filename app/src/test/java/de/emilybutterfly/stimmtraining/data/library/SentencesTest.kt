package de.emilybutterfly.stimmtraining.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SentencesTest {
    private fun split(text: String): List<String> {
        assertWellFormed(text)
        return Sentences.ranges(text).map { text.substring(it) }
    }

    /** Aufsteigend, ohne Überlappung, nie leer, ohne Leerraum am Rand, jedes sichtbare Zeichen in einem Satz. */
    private fun assertWellFormed(text: String) {
        val ranges = Sentences.ranges(text)
        var covered = 0
        var previousEnd = 0
        for (range in ranges) {
            assertTrue("leer: $range in »$text«", !range.isEmpty())
            assertTrue("überlappt: $range in »$text«", range.first >= previousEnd)
            assertFalse("Leerraum vorn: $range in »$text«", text[range.first].isWhitespace())
            assertFalse("Leerraum hinten: $range in »$text«", text[range.last].isWhitespace())
            assertFalse("Zeilenumbruch im Satz: $range in »$text«", text.substring(range).contains('\n'))
            covered += text.substring(range).count { !it.isWhitespace() }
            previousEnd = range.last + 1
        }
        assertEquals("nicht alle Zeichen in Sätzen: »$text«", text.count { !it.isWhitespace() }, covered)
    }

    @Test
    fun simpleSentences() {
        assertEquals(
            listOf("Einst stritten sich Nordwind und Sonne.", "Wer war der Stärkere?", "Da kam ein Wanderer!"),
            split("Einst stritten sich Nordwind und Sonne. Wer war der Stärkere? Da kam ein Wanderer!"),
        )
    }

    @Test
    fun abbreviationsDoNotEndSentences() {
        assertEquals(
            listOf("Wir üben z. B. das Summen.", "Dann erklärt Dr. Müller die Atmung.", "Die Übung steht auf S. 12 im Heft."),
            split("Wir üben z. B. das Summen. Dann erklärt Dr. Müller die Atmung. Die Übung steht auf S. 12 im Heft."),
        )
        assertEquals(listOf("Man kann z.B. Atemübungen machen.", "Oder nicht."), split("Man kann z.B. Atemübungen machen. Oder nicht."))
        assertEquals(listOf("Er kam ca. Mitternacht, d. h. spät.", "Prof. Schmidt bzw. Hr. Meier kamen auch."), split("Er kam ca. Mitternacht, d. h. spät. Prof. Schmidt bzw. Hr. Meier kamen auch."))
        // Groß- und Kleinschreibung der Abkürzung ist egal.
        assertEquals(listOf("VGL. Kapitel drei."), split("VGL. Kapitel drei."))
        assertEquals(listOf("Siehe St. Pauli, Nr. 5 und Abb. 3."), split("Siehe St. Pauli, Nr. 5 und Abb. 3."))
        // Einzelne Buchstaben gelten immer als Abkürzung, auch „Plan B.“.
        assertEquals(listOf("Das ist Plan B. Dann geht es los."), split("Das ist Plan B. Dann geht es los."))
        // Ein unbekanntes Wort oder eine Maßangabe beendet den Satz.
        assertEquals(listOf("Er wohnt in der Goethestr.", "Dann zog er um."), split("Er wohnt in der Goethestr. Dann zog er um."))
        assertEquals(listOf("Er lief 100m.", "Dann war er müde."), split("Er lief 100m. Dann war er müde."))
    }

    @Test
    fun ordinalsAndDatesDoNotEndSentencesButYearsDo() {
        assertEquals(listOf("Am 3. Oktober ist Feiertag.", "Am 1. 2. 2020 war Samstag."), split("Am 3. Oktober ist Feiertag. Am 1. 2. 2020 war Samstag."))
        assertEquals(listOf("Der 12. Versuch klappte."), split("Der 12. Versuch klappte."))
        assertEquals(listOf("Das war im Jahr 1990.", "Dann kam die Wende."), split("Das war im Jahr 1990. Dann kam die Wende."))
        assertEquals(listOf("Seit 2024.", "Neu."), split("Seit 2024. Neu."))
        assertEquals(listOf("Es kostet 3.50 Euro.", "Gut."), split("Es kostet 3.50 Euro. Gut."))
        assertEquals(listOf("Format A4.", "Quer."), split("Format A4. Quer."))
    }

    @Test
    fun quotesAndBracketsBelongToTheSentence() {
        assertEquals(
            listOf("„Komm her!“, rief sie.", "„Sofort!“", "Er kam."),
            split("„Komm her!“, rief sie. „Sofort!“ Er kam."),
        )
        assertEquals(listOf("»Ja.«", "Sie nickte.", "»Wirklich?«", "»Ja!«"), split("»Ja.« Sie nickte. »Wirklich?« »Ja!«"))
        assertEquals(listOf("«Grüezi.»", "‹So.›"), split("«Grüezi.» ‹So.›"))
        assertEquals(listOf("„Wohin?“ fragte er leise."), split("„Wohin?“ fragte er leise."))
        assertEquals(listOf("(Das ist wichtig.)", "Danach geht es weiter."), split("(Das ist wichtig.) Danach geht es weiter."))
        assertEquals(listOf("He said \"stop.\"", "Then 'go.'", "Fine’s.’"), split("He said \"stop.\" Then 'go.' Fine’s.’"))
        assertEquals(listOf("[Pause.]", "Weiter."), split("[Pause.] Weiter."))
    }

    @Test
    fun ellipsisAndRunsOfPunctuation() {
        assertEquals(listOf("Er zögerte … und dann sprang er."), split("Er zögerte … und dann sprang er."))
        assertEquals(listOf("Und dann...", "Stille.", "Nichts."), split("Und dann... Stille. Nichts."))
        assertEquals(listOf("Warte…", "Jetzt!"), split("Warte… Jetzt!"))
        assertEquals(listOf("… und weiter geht’s."), split("… und weiter geht’s."))
        assertEquals(listOf("Wie bitte?!", "Das glaube ich nicht!!!", "Wirklich?"), split("Wie bitte?! Das glaube ich nicht!!! Wirklich?"))
        // Punkte ohne Leerraum danach trennen nicht.
        assertEquals(listOf("Siehe www.example.org.", "Dort steht mehr."), split("Siehe www.example.org. Dort steht mehr."))
        assertEquals(listOf("Wa...rum nicht?"), split("Wa...rum nicht?"))
    }

    @Test
    fun lowercaseContinuationKeepsTheSentence() {
        assertEquals(listOf("Er sagte nein. aber dann doch ja."), split("Er sagte nein. aber dann doch ja."))
        assertEquals(listOf("Hilfe! rief er.", "Niemand kam."), split("Hilfe! rief er. Niemand kam."))
        assertEquals(listOf("Es war ca. ärgerlich."), split("Es war ca. ärgerlich."))
    }

    @Test
    fun lineBreaksAlwaysEndSentences() {
        assertEquals(
            listOf("Der Mond ist aufgegangen,", "Die goldnen Sternlein prangen", "Am Himmel hell und klar."),
            split("Der Mond ist aufgegangen,\nDie goldnen Sternlein prangen\nAm Himmel hell und klar."),
        )
        assertEquals(listOf("Ein Satz, der", "über zwei Zeilen geht."), split("Ein Satz, der\nüber zwei Zeilen geht."))
        assertEquals(listOf("Zeile eins", "Zeile zwei"), split("Zeile eins  \n  Zeile zwei"))
        assertEquals(listOf("a", "b"), split("a\n\n\n b"))
        assertEquals(listOf("Am 3.", "Oktober"), split("Am 3.\nOktober"))
        val text = "Zeile eins\nZeile zwei"
        assertEquals(listOf(0 until 10, 11 until 21), Sentences.ranges(text))
    }

    @Test
    fun textWithoutTerminatorIsOneSentence() {
        assertEquals(listOf("Ohne Punkt und Komma"), split("Ohne Punkt und Komma"))
        assertEquals(listOf("Erster Satz.", "Rest ohne Punkt"), split("Erster Satz. Rest ohne Punkt"))
        assertEquals(listOf("."), split("."))
        assertEquals(listOf("?!"), split("  ?!  "))
    }

    @Test
    fun emojiAndWhitespace() {
        assertEquals(listOf("Super gemacht 🎉!", "Weiter so 💪.", "😀"), split("Super gemacht 🎉! Weiter so 💪. 😀"))
        assertEquals(emptyList<IntRange>(), Sentences.ranges(""))
        assertEquals(emptyList<IntRange>(), Sentences.ranges("   \n\t \u00A0"))
        assertEquals(listOf(2 until 8), Sentences.ranges("  Hallo.  "))
        assertEquals(listOf("Satz\teins.", "Satz zwei."), split("Satz\teins.\u00A0Satz zwei."))
        assertEquals(listOf("z.\u00A0B. das hier."), split("z.\u00A0B. das hier."))
    }

    @Test
    fun rangesAreWellFormedForArbitraryText() {
        val alphabet = "aZ ß.!?…„“\"'»«)(]\n\t\u00A01234 z.B. usw. Dr. 🎉"
        val random = Random(7)
        repeat(2_000) {
            val text = String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.length)] })
            // Halbe Emoji-Ersatzpaare am Rand sind hier egal: geprüft wird nur der Aufbau der Bereiche.
            assertWellFormed(text)
        }
    }
}
