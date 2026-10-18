package de.emilybutterfly.stimmtraining.data.document

/**
 * Markdown, bewusst einfach und fehlertolerant: Überschriften, Listen, Zitate, Codeblöcke,
 * fett und kursiv. Zeilenumbrüche innerhalb eines Absatzes bleiben – anders als in HTML-
 * Ansichten – erhalten, weil Lesetexte und Gedichte davon leben.
 */
internal object MarkdownReader {
    // Keine wiederholten Gruppen und kein „.*?“ vor Leerraum: Bei sehr langen Zeilen liefe die
    // Regex sonst quadratisch lange oder in einen StackOverflowError. Regeln und Zitate per Hand.
    private val ATX_HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*))?$""")
    private val SETEXT_1 = Regex("""^ {0,3}=+[ \t]*$""")
    private val SETEXT_2 = Regex("""^ {0,3}-+[ \t]*$""")
    private val FENCE = Regex("""^([ \t]*)(`{3,}|~{3,})(.*)$""")
    private val BULLET = Regex("""^([ \t]*)([-*+])(?:[ \t]+(.*))?$""")
    private val ORDERED = Regex("""^([ \t]*)(\d{1,9})[.)](?:[ \t]+(.*))?$""")
    private val LINK_DEFINITION = Regex("""^ {0,3}\[[^\]]+]:\s*\S.*$""")
    private val FRONT_MATTER_KEY = Regex("""^[A-Za-z0-9_-]+\s*:""")

    fun read(text: String, builder: TextDocumentBuilder) {
        val lines = text.lines()
        val parser = Parser(builder)
        for (index in frontMatterEnd(lines) until lines.size) {
            if (builder.isFull) return
            parser.line(lines[index])
        }
        parser.close()
    }

    /** YAML-Kopf (`---` … `---`) mancher Editoren überspringen. */
    private fun frontMatterEnd(lines: List<String>): Int {
        if (lines.size < 3 || lines[0].trimEnd() != "---" || !FRONT_MATTER_KEY.containsMatchIn(lines[1])) return 0
        for (i in 1 until minOf(lines.size, 200)) {
            val line = lines[i].trimEnd()
            if (line == "---" || line == "...") return i + 1
        }
        return 0
    }

    /** Text einer ATX-Überschrift ohne schließende Rauten ("## Titel ##"); nur Rauten bleiben stehen. */
    private fun atxText(content: String): String {
        val text = content.trimEnd('#')
        return if (text.endsWith(' ') || text.endsWith('\t')) text.trimEnd(' ', '\t') else content
    }

    /** Trennlinie: mindestens drei gleiche *, - oder _, dazwischen nur Leerraum. */
    private fun isRule(line: String): Boolean {
        var i = 0
        while (i < 3 && i < line.length && line[i] == ' ') i++
        val c = line.getOrNull(i) ?: return false
        if (c != '*' && c != '-' && c != '_') return false
        var count = 0
        for (k in i until line.length) {
            when (line[k]) {
                c -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    /** Länge der Zitatzeichen am Zeilenanfang ("> ", auch "> > "), 0 ohne. */
    private fun quotePrefix(line: String): Int {
        var end = 0
        while (true) {
            var i = end
            while (i < end + 3 && i < line.length && line[i] == ' ') i++
            if (i >= line.length || line[i] != '>') return end
            i++
            if (i < line.length && line[i] == ' ') i++
            end = i
        }
    }

    /** Einrückung in Spalten; Tabulatoren springen auf das nächste Vielfache von 4. */
    private fun width(indent: String): Int = indent.fold(0) { column, c -> if (c == '\t') column + 4 - column % 4 else column + 1 }

    private class Parser(private val builder: TextDocumentBuilder) {
        /** Art des offenen Absatzes, null = keiner. */
        private var style: BlockStyle? = null
        private var marker: String? = null
        private var level = 0
        private val lines = ArrayList<String>()
        private var fence: String? = null
        private var fenceIndent = 0

        /** Einrückungen der offenen Listenebenen. */
        private val listIndents = ArrayList<Int>()

        fun line(raw: String) {
            val line = raw.trimEnd()
            val fence = fence
            if (fence != null) {
                val trimmed = line.trim()
                if (trimmed.length >= fence.length && trimmed.all { it == fence[0] }) {
                    close()
                } else {
                    lines += line.drop(minOf(fenceIndent, line.length - line.trimStart().length))
                }
                return
            }
            if (line.isEmpty()) {
                close()
                return
            }
            FENCE.matchEntire(line)?.let { match ->
                val opening = match.groupValues[2]
                if (opening[0] != '`' || '`' !in match.groupValues[3]) {
                    close()
                    listIndents.clear()
                    style = BlockStyle.Paragraph
                    this.fence = opening
                    fenceIndent = width(match.groupValues[1])
                    return
                }
            }
            ATX_HEADING.matchEntire(line)?.let { match ->
                close()
                listIndents.clear()
                lines += atxText(match.groupValues[2])
                style = StyleNames.heading(match.groupValues[1].length)
                close()
                return
            }
            if (style == BlockStyle.Paragraph) {
                if (SETEXT_1.matches(line)) return close(BlockStyle.Heading1)
                if (SETEXT_2.matches(line)) return close(BlockStyle.Heading2)
            }
            if (isRule(line)) {
                close()
                listIndents.clear()
                return
            }
            val quote = quotePrefix(line)
            if (quote > 0) {
                val content = line.substring(quote).trim()
                if (content.isEmpty()) {
                    close()
                    return
                }
                if (style != BlockStyle.Quote) {
                    close()
                    listIndents.clear()
                    style = BlockStyle.Quote
                }
                lines += content
                return
            }
            val bullet = BULLET.matchEntire(line)
            val item = bullet ?: ORDERED.matchEntire(line)
            if (item != null) {
                val content = item.groupValues[3]
                // Wie in CommonMark: Mitten in einem Absatz beginnt nur "- …" oder "1. …" eine Liste.
                val starts = style != BlockStyle.Paragraph ||
                    content.isNotBlank() && (bullet != null || item.groupValues[2] == "1")
                if (starts) {
                    close()
                    val indent = width(item.groupValues[1])
                    while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeAt(listIndents.lastIndex)
                    if (listIndents.isEmpty() || listIndents.last() < indent) listIndents += indent
                    style = BlockStyle.ListItem
                    level = listIndents.size - 1
                    marker = if (bullet != null) "•" else item.groupValues[2] + "."
                    lines += content.trim()
                    return
                }
            }
            if (style == null && LINK_DEFINITION.matches(line)) return
            if (style == null) {
                val indent = width(line.takeWhile { it == ' ' || it == '\t' })
                if (listIndents.isNotEmpty() && indent > listIndents.first()) {
                    // Weiterer Absatz eines Listenpunkts: eingerückt, ohne Aufzählungszeichen.
                    style = BlockStyle.ListItem
                    marker = null
                    level = (listIndents.count { it < indent } - 1).coerceAtLeast(0)
                } else {
                    listIndents.clear()
                    style = BlockStyle.Paragraph
                }
            }
            lines += line.trim()
        }

        /** Schließt den offenen Absatz ab; [override] macht ihn z. B. zur Setext-Überschrift. */
        fun close(override: BlockStyle? = null) {
            val style = style
            if (style != null) {
                val blockStyle = override ?: style
                val item = style == BlockStyle.ListItem
                var blockMarker = if (item) marker else null
                val blockLevel = if (item) level else 0
                // Lange Absätze werden an Zeilenumbrüchen geteilt; das Aufzählungszeichen bekommt nur der erste Teil.
                val split = {
                    builder.endBlock(blockStyle, blockMarker, blockLevel)
                    blockMarker = null
                }
                if (fence != null) {
                    lines.forEachIndexed { index, line ->
                        if (index > 0) {
                            if (builder.currentBlockLength > TextDocumentBuilder.LONG_BLOCK_CHARS) split() else builder.lineBreak()
                        }
                        builder.append(line)
                    }
                } else {
                    MarkdownInline.emit(lines.joinToString("\n"), builder, split)
                }
                builder.endBlock(blockStyle, blockMarker, blockLevel)
            }
            lines.clear()
            this.style = null
            fence = null
            marker = null
            level = 0
        }
    }
}

/**
 * Inline-Auszeichnung nach dem Begrenzer-Verfahren von CommonMark (vereinfacht): `**fett**`,
 * `*kursiv*`, `_kursiv_` nur an Wortgrenzen, Code, Links, Escapes, HTML-Tags und Entitäten.
 * Was nicht passt, bleibt wörtlich stehen (`5 * 3 = 15`).
 */
private object MarkdownInline {
    private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Bei mehr Begrenzern bleibt alles wörtlich – schützt vor quadratischer Laufzeit. */
    private const val MAX_DELIMITERS = 2_000
    private const val MAX_LINK_LENGTH = 2_000
    private const val MAX_NESTING = 4

    /** Längere Autolinks und Tags bleiben Text; so durchsucht nicht jedes '<' den ganzen Absatz. */
    private const val MAX_HTML_LENGTH = 2_000

    /** Womit eine E-Mail-Adresse außer Buchstaben und Ziffern beginnen kann (umfasst '/' und '!'). */
    private const val EMAIL_PUNCTUATION = ".!#$%&'*+/=?^_`{|}~-"

    private val AUTOLINK = Regex("""<([A-Za-z][A-Za-z0-9+.\-]{1,31}:[^<>\s]*)>""")
    // "*+" (possessiv) wiederholt die Gruppe ohne Rekursion; gefunden wird dasselbe.
    private val EMAIL = Regex("""<([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*+)>""")
    private val TAG = Regex("""</?([A-Za-z][A-Za-z0-9-]*)(?:\s[^<>]*)?/?>""")

    private class Text(val text: String)

    /** Was die Suche in einem Absatz schon weiß; vergebliche Suchen bis zum Ende laufen so nur einmal. */
    private class Scan {
        /** Ab hier gibt es kein "-->" mehr. */
        var noCommentEnd = Int.MAX_VALUE

        /** Je Länge einer Backtick-Folge: wo die letzte solche Folge beginnt. */
        var backtickRuns: Map<Int, Int>? = null
    }

    private class Delimiter(val char: Char, val length: Int, val canOpen: Boolean, val canClose: Boolean) {
        var count = length
        var active = true
    }

    /** [split] beendet den Absatz, wenn er an einem Zeilenumbruch schon zu lang ist. */
    fun emit(source: String, builder: TextDocumentBuilder, split: () -> Unit) {
        val tokens = ArrayList<Any>()
        tokenize(source, tokens, 0)
        val bold = IntArray(tokens.size + 1)
        val italic = IntArray(tokens.size + 1)
        matchEmphasis(tokens, bold, italic)
        var boldDepth = 0
        var italicDepth = 0
        for (index in tokens.indices) {
            boldDepth += bold[index]
            italicDepth += italic[index]
            val text = when (val token = tokens[index]) {
                is Text -> token.text
                is Delimiter -> token.char.toString().repeat(token.count)
                else -> ""
            }
            append(text, builder, boldDepth > 0, italicDepth > 0, split)
        }
    }

    private fun append(text: String, builder: TextDocumentBuilder, bold: Boolean, italic: Boolean, split: () -> Unit) {
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end < 0) return builder.append(text.substring(start), bold, italic)
            builder.append(text.substring(start, end), bold, italic)
            if (builder.currentBlockLength > TextDocumentBuilder.LONG_BLOCK_CHARS) split() else builder.append("\n", bold, italic)
            start = end + 1
        }
    }

    private fun tokenize(s: String, out: MutableList<Any>, nesting: Int) {
        val scan = Scan()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) out += Text(text.toString())
            text.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            when {
                c == '\\' && next != null && next in ASCII_PUNCTUATION -> {
                    text.append(next)
                    i += 2
                }
                // Backslash am Zeilenende = harter Umbruch; den Umbruch gibt es hier ohnehin.
                c == '\\' && next == '\n' -> i++
                c == '`' -> {
                    val run = runLength(s, i)
                    // Nur suchen, wenn dahinter überhaupt eine Folge gleicher Länge steht.
                    val runs = scan.backtickRuns ?: backtickRuns(s).also { scan.backtickRuns = it }
                    val close = if ((runs[run] ?: -1) >= i + run) closingBackticks(s, i + run, run) else -1
                    if (close < 0) {
                        text.append(s, i, i + run)
                        i += run
                    } else {
                        var code = s.substring(i + run, close).replace('\n', ' ')
                        if (code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.isNotBlank()) {
                            code = code.substring(1, code.length - 1)
                        }
                        text.append(code)
                        i = close + run
                    }
                }
                (c == '[' || c == '!' && next == '[') && nesting < MAX_NESTING -> {
                    val start = if (c == '!') i + 1 else i
                    val link = link(s, start)
                    if (link == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        tokenize(link.first, out, nesting + 1)
                        i = link.second
                    }
                }
                c == '<' -> {
                    val html = html(s, i, scan)
                    if (html == null) {
                        text.append(c)
                        i++
                    } else {
                        text.append(html.first)
                        i = html.second
                    }
                }
                c == '&' -> {
                    val entity = HtmlEntities.decodeAt(s, i)
                    if (entity == null) {
                        text.append(c)
                        i++
                    } else {
                        text.append(entity.first)
                        i = entity.second
                    }
                }
                c == '*' || c == '_' -> {
                    val run = runLength(s, i)
                    val before = if (i > 0) s[i - 1] else ' '
                    val after = if (i + run < s.length) s[i + run] else ' '
                    if (c == '*' && run == 1 && before.isLetterOrDigit() && after.isLetterOrDigit()) {
                        // Abweichung von CommonMark: Der Genderstern („Lehrer*innen“) bleibt stehen.
                        text.append(c)
                        i++
                        continue
                    }
                    flush()
                    val leftFlanking = !after.isWhitespace() && (!after.isPunctuation() || before.isWhitespace() || before.isPunctuation())
                    val rightFlanking = !before.isWhitespace() && (!before.isPunctuation() || after.isWhitespace() || after.isPunctuation())
                    out += if (c == '*') {
                        Delimiter(c, run, leftFlanking, rightFlanking)
                    } else {
                        Delimiter(
                            c, run,
                            canOpen = leftFlanking && (!rightFlanking || before.isPunctuation()),
                            canClose = rightFlanking && (!leftFlanking || after.isPunctuation()),
                        )
                    }
                    i += run
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /**
     * Ordnet öffnende und schließende Begrenzer einander zu (CommonMark „process emphasis“) und
     * vermerkt in [bold]/[italic] Beginn (+1) und Ende (−1) der Hervorhebung je Token.
     */
    private fun matchEmphasis(tokens: List<Any>, bold: IntArray, italic: IntArray) {
        val delimiters = tokens.indices.filter { tokens[it] is Delimiter }
        if (delimiters.size > MAX_DELIMITERS) return
        val bottom = HashMap<Int, Int>()
        for (ci in delimiters.indices) {
            val closer = tokens[delimiters[ci]] as Delimiter
            if (!closer.canClose) continue
            while (closer.count > 0) {
                val key = (if (closer.char == '*') 0 else 6) + (if (closer.canOpen) 3 else 0) + closer.length % 3
                var oi = ci - 1
                var found = -1
                while (oi > (bottom[key] ?: -1)) {
                    val opener = tokens[delimiters[oi]] as Delimiter
                    if (opener.active && opener.count > 0 && opener.char == closer.char && opener.canOpen) {
                        // „Dreierregel“: z. B. *a**b* ist kein Paar aus * und **.
                        val odd = (opener.canClose || closer.canOpen) &&
                            (opener.length + closer.length) % 3 == 0 &&
                            !(opener.length % 3 == 0 && closer.length % 3 == 0)
                        if (!odd) {
                            found = oi
                            break
                        }
                    }
                    oi--
                }
                if (found < 0) {
                    bottom[key] = ci - 1
                    if (!closer.canOpen) closer.active = false
                    break
                }
                val opener = tokens[delimiters[found]] as Delimiter
                val strong = opener.count >= 2 && closer.count >= 2
                val used = if (strong) 2 else 1
                opener.count -= used
                closer.count -= used
                val marks = if (strong) bold else italic
                marks[delimiters[found] + 1]++
                marks[delimiters[ci]]--
                for (k in found + 1 until ci) (tokens[delimiters[k]] as Delimiter).active = false
            }
        }
    }

    private fun runLength(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end] == s[start]) end++
        return end - start
    }

    /** Länge → Beginn der letzten Backtick-Folge dieser Länge. */
    private fun backtickRuns(s: String): Map<Int, Int> {
        val runs = HashMap<Int, Int>()
        var i = s.indexOf('`')
        while (i >= 0) {
            val run = runLength(s, i)
            runs[run] = i
            i = s.indexOf('`', i + run)
        }
        return runs
    }

    private fun closingBackticks(s: String, from: Int, length: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                val run = runLength(s, i)
                if (run == length) return i
                i += run
            } else {
                i++
            }
        }
        return -1
    }

    /** `[Text](Ziel)` oder `[Text][Verweis]` ab [start] ('['): Linktext und Position dahinter. */
    private fun link(s: String, start: Int): Pair<String, Int>? {
        val close = matching(s, start, '[', ']') ?: return null
        val end = when (s.getOrNull(close + 1)) {
            '(' -> matching(s, close + 1, '(', ')')
            '[' -> matching(s, close + 1, '[', ']')
            else -> null
        } ?: return null
        return s.substring(start + 1, close) to end + 1
    }

    private fun matching(s: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = start
        val limit = minOf(s.length, start + MAX_LINK_LENGTH)
        while (i < limit) {
            when (s[i]) {
                '\\' -> i++
                open -> depth++
                close -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /**
     * Autolinks werden zu ihrem Text, `<br>` zum Umbruch, andere HTML-Tags verschwinden.
     * Die Ausdrücke laufen nur über das kurze Stück bis zum '>': Auf Android kopiert jeder
     * Matcher seine ganze Eingabe, bei vielen '<' im Absatz wäre das quadratisch.
     */
    private fun html(s: String, start: Int, scan: Scan): Pair<String, Int>? {
        val next = s.getOrNull(start + 1) ?: return null
        if (!(next in 'a'..'z' || next in 'A'..'Z' || next in '0'..'9' || next in EMAIL_PUNCTUATION)) return null
        // Autolinks, Adressen und Tags enden am ersten '>' und enthalten kein '<'.
        val limit = minOf(s.length, start + MAX_HTML_LENGTH)
        var end = start + 1
        while (end < limit && s[end] != '>' && s[end] != '<') end++
        val candidate = if (end < limit && s[end] == '>') s.substring(start, end + 1) else null
        if (candidate != null) {
            AUTOLINK.matchEntire(candidate)?.let { return it.groupValues[1] to end + 1 }
            EMAIL.matchEntire(candidate)?.let { return it.groupValues[1] to end + 1 }
        }
        if (s.startsWith("<!--", start)) commentEnd(s, start, scan)?.let { return "" to it }
        if (candidate != null) {
            TAG.matchEntire(candidate)?.let { return (if (it.groupValues[1].equals("br", ignoreCase = true)) "\n" else "") to end + 1 }
        }
        return null
    }

    /** Position hinter dem "-->" eines Kommentars ab [start]; null, wenn er nicht endet. */
    private fun commentEnd(s: String, start: Int, scan: Scan): Int? {
        val from = start + 4
        if (from >= scan.noCommentEnd) return null
        val end = s.indexOf("-->", from)
        if (end < 0) {
            scan.noCommentEnd = from
            return null
        }
        return end + 3
    }

    private fun Char.isPunctuation(): Boolean = this in ASCII_PUNCTUATION || when (Character.getType(this).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }
}
