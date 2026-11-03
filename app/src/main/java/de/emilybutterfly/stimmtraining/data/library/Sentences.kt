package de.emilybutterfly.stimmtraining.data.library

import java.util.Locale

/**
 * Zerlegt den Text eines Absatzes in Sätze, damit man den zuletzt gelesenen antippen kann.
 * Abgestimmt auf deutsche Texte: Abkürzungen („z. B.“, „Dr.“) und Ordnungszahlen („am 3. Oktober“)
 * beenden keinen Satz, ein Zeilenumbruch dagegen immer – in Gedichten ist so jede Zeile antippbar.
 */
object Sentences {
    private const val TERMINATORS = ".!?…"

    /** Anführungszeichen und Klammern, die hinter dem Satzzeichen noch zum Satz gehören. */
    private const val CLOSERS = "”“\"'»«›‹)]’‘"

    /** Wörter, nach deren Punkt der Satz weitergeht (klein geschrieben, ohne Punkt). Einzelbuchstaben gelten immer. */
    private val ABBREVIATIONS = setOf(
        "bzw", "ca", "usw", "etc", "vgl", "ggf", "evtl", "inkl", "zzgl", "bspw", "insb", "sog",
        "dr", "prof", "nr", "st", "str", "hr", "fr", "mio", "mrd", "jh", "jhd", "abs", "abb", "anm",
        "bd", "bsp", "chr", "dt", "engl", "geb", "gest", "hl", "kap", "lt", "max", "min", "mind",
        "tel", "verh", "vs", "zit", "ebd", "ff", "al", "sen", "jun", "gr", "kl", "mr", "mrs", "ms",
    )
    private val MAX_ABBREVIATION = ABBREVIATIONS.maxOf { it.length }

    /**
     * Die Sätze in [text] als Bereiche `start until ende`, aufsteigend und ohne Überlappung. Ein Satz
     * beginnt ohne Leerraum und endet hinter dem Satzzeichen samt schließender Anführungszeichen und
     * Klammern; ein Zeilenumbruch gehört zu keinem Satz. Jedes sichtbare Zeichen liegt in genau einem Satz.
     */
    fun ranges(text: String): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = -1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> {
                    if (start >= 0) result += start until trimmedEnd(text, start, i)
                    start = -1
                    i++
                }
                c.isWhitespace() -> i++
                else -> {
                    if (start < 0) start = i
                    if (c in TERMINATORS) {
                        var end = i
                        while (end < text.length && text[end] in TERMINATORS) end++
                        val terminatorsEnd = end
                        while (end < text.length && text[end] in CLOSERS) end++
                        if (endsSentence(text, i, terminatorsEnd, end)) {
                            result += start until end
                            start = -1
                        }
                        i = end
                    } else {
                        i++
                    }
                }
            }
        }
        if (start >= 0) result += start until trimmedEnd(text, start, text.length)
        return result
    }

    /** Ob die Satzzeichen [from] bis [terminatorsEnd] (danach Schließendes bis [end]) einen Satz beenden. */
    private fun endsSentence(text: String, from: Int, terminatorsEnd: Int, end: Int): Boolean {
        if (end < text.length && !text[end].isWhitespace()) return false
        // „… und dann“, „z. B. das“: Geht es klein weiter, ist der Satz nicht zu Ende.
        var next = end
        while (next < text.length && text[next] != '\n' && text[next].isWhitespace()) next++
        if (next < text.length && text[next].isLowerCase()) return false
        // Abkürzungen und Ordnungszahlen gibt es nur mit einem einzelnen Punkt; mit Klammer oder
        // Anführungszeichen dahinter endet eher der Satz („… usw.“ Dann).
        val singlePeriod = terminatorsEnd - from == 1 && text[from] == '.' && end == terminatorsEnd
        return !(singlePeriod && (isAbbreviation(text, from) || isOrdinal(text, from)))
    }

    /** „z. B.“, „S. 5“, „Dr.“: ein bekanntes Wort oder ein einzelner Buchstabe vor dem Punkt bei [period]. */
    private fun isAbbreviation(text: String, period: Int): Boolean {
        var start = period
        while (start > 0 && text[start - 1].isLetter() && period - start <= MAX_ABBREVIATION) start--
        val length = period - start
        if (length == 0 || length > MAX_ABBREVIATION) return false
        // „100m.“ ist keine Abkürzung, sondern eine Maßangabe am Satzende.
        if (start > 0 && text[start - 1].isLetterOrDigit()) return false
        return length == 1 || text.substring(start, period).lowercase(Locale.ROOT) in ABBREVIATIONS
    }

    /** „am 3. Oktober“, „1. 2. 2020“: höchstens zwei Ziffern vor dem Punkt. Jahreszahlen beenden den Satz. */
    private fun isOrdinal(text: String, period: Int): Boolean {
        var start = period
        while (start > 0 && text[start - 1].isDigit() && period - start <= 2) start--
        val digits = period - start
        return digits in 1..2 && (start == 0 || !text[start - 1].isLetterOrDigit())
    }

    private fun trimmedEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1].isWhitespace()) e--
        return e
    }
}
