package de.emilybutterfly.stimmtraining.data.document

import java.util.Locale

/**
 * HTML mit einem kleinen, fehlertoleranten Tag-Durchlauf: Blockelemente werden zu Absätzen,
 * h1–h6 zu Überschriften, li zu Listenpunkten, blockquote zu Zitaten, b/strong und i/em zu
 * fett und kursiv. Leerraum wird wie im Browser zusammengefasst (außer in `<pre>`).
 */
internal object HtmlReader {
    fun read(html: String, builder: TextDocumentBuilder) = Walker(html, builder).run()

    private val BLOCKS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "menu", "dir", "li", "dl", "dt", "dd",
        "blockquote", "pre", "listing", "table", "caption", "thead", "tbody", "tfoot", "tr", "section", "article",
        "header", "footer", "nav", "aside", "main", "figure", "figcaption", "address", "form", "fieldset",
        "legend", "details", "summary", "center", "hgroup", "body", "html",
    )
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val LISTS = setOf("ul", "ol", "menu", "dir")

    /** Elemente, deren Inhalt nicht zum lesbaren Text gehört. */
    private val SKIPPED = setOf(
        "head", "script", "style", "title", "noscript", "template", "svg", "math", "iframe", "object",
        "select", "textarea", "audio", "video", "canvas", "map",
    )
    private val VOID = setOf(
        "br", "hr", "img", "input", "meta", "link", "wbr", "area", "base", "col", "embed", "source", "track", "param",
    )
    private val BOLD = setOf("b", "strong")
    private val ITALIC = setOf("i", "em", "cite", "var", "dfn")
    private val STYLE_BOLD = Regex("""font-weight\s*:\s*(?:bold|bolder|[6-9]00)""", RegexOption.IGNORE_CASE)
    private val STYLE_ITALIC = Regex("""font-style\s*:\s*(?:italic|oblique)""", RegexOption.IGNORE_CASE)

    private class Block(val tag: String) {
        var marker: String? = null
        var counter = 1
    }

    private class Inline(val tag: String, val bold: Boolean, val italic: Boolean)

    private class Walker(private val s: String, private val builder: TextDocumentBuilder) {
        private val blocks = ArrayList<Block>()
        private val inlines = ArrayList<Inline>()
        private var boldCount = 0
        private var italicCount = 0
        private var pre = 0
        private var skipNewline = false
        private val text = StringBuilder()
        private var pendingSpace = false
        private var lineHasContent = false
        private var blockHasText = false

        /** Ab dieser Position gibt es kein schließendes '>' mehr; '<' ist dann normaler Text. */
        private var noTagEnd = Int.MAX_VALUE

        /**
         * Je übersprungenem Element (head, script …) die Position des nächsten "</name" ab der
         * letzten Suche, -1 für „keins mehr“. Ohne das suchte jedes weitere `<head>` wieder bis zum Ende.
         */
        private val closingTags = HashMap<String, Int>()

        fun run() {
            var i = 0
            while (i < s.length && !builder.isFull) {
                i = when (s[i]) {
                    '<' -> tag(i)
                    '&' -> entity(i)
                    else -> {
                        char(s[i])
                        i + 1
                    }
                }
            }
            boundary()
        }

        private fun char(c: Char) {
            if (skipNewline) {
                skipNewline = false
                if (c == '\n') return
            }
            if (pre > 0) {
                if (c == '\n') {
                    lineBreak()
                } else {
                    text.append(c)
                    if (!c.isWhitespace()) blockHasText = true
                }
                return
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000C') {
                if (lineHasContent) pendingSpace = true
                return
            }
            if (pendingSpace) {
                text.append(' ')
                pendingSpace = false
            }
            text.append(c)
            lineHasContent = true
            if (!c.isWhitespace()) blockHasText = true
        }

        private fun entity(i: Int): Int {
            val entity = HtmlEntities.decodeAt(s, i)
            if (entity == null) {
                char('&')
                return i + 1
            }
            entity.first.forEach(::char)
            return entity.second
        }

        private fun tag(i: Int): Int {
            if (s.startsWith("<!--", i)) {
                val end = s.indexOf("-->", i + 4)
                return if (end < 0) s.length else end + 3
            }
            val next = s.getOrNull(i + 1)
            if (next == '!' || next == '?') {
                val end = s.indexOf('>', i)
                return if (end < 0) s.length else end + 1
            }
            val closing = next == '/'
            var j = i + if (closing) 2 else 1
            if (i >= noTagEnd || j >= s.length || !(s[j] in 'a'..'z' || s[j] in 'A'..'Z')) {
                char('<')
                return i + 1
            }
            val nameStart = j
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-' || s[j] == ':')) j++
            val name = s.substring(nameStart, j).lowercase(Locale.ROOT)

            // Attribute bis zum '>' lesen, Anführungszeichen beachten.
            var attributes: MutableMap<String, String>? = null
            var selfClosing = false
            while (true) {
                while (j < s.length && s[j].isWhitespace()) j++
                if (j >= s.length) {
                    noTagEnd = i
                    char('<')
                    return i + 1
                }
                val c = s[j]
                if (c == '>') {
                    j++
                    break
                }
                if (c == '/') {
                    selfClosing = s.getOrNull(j + 1) == '>'
                    j++
                    continue
                }
                val attributeStart = j
                while (j < s.length && !s[j].isWhitespace() && s[j] != '=' && s[j] != '>' && s[j] != '/') j++
                if (j == attributeStart) j++ // z. B. ein einzelnes '"'
                val attribute = s.substring(attributeStart, j).lowercase(Locale.ROOT)
                while (j < s.length && s[j].isWhitespace()) j++
                var value = ""
                if (j < s.length && s[j] == '=') {
                    j++
                    while (j < s.length && s[j].isWhitespace()) j++
                    val quote = s.getOrNull(j)
                    if (quote == '"' || quote == '\'') {
                        val end = s.indexOf(quote, j + 1)
                        if (end < 0) {
                            j = s.length
                            continue
                        }
                        value = s.substring(j + 1, end)
                        j = end + 1
                    } else {
                        val valueStart = j
                        while (j < s.length && !s[j].isWhitespace() && s[j] != '>') j++
                        value = s.substring(valueStart, j)
                    }
                }
                (attributes ?: HashMap<String, String>().also { attributes = it })[attribute] = value
            }

            flushText()
            if (closing) {
                endTag(name)
                return j
            }
            if (name in SKIPPED && !selfClosing) {
                val end = closingTag(name, j)
                return when {
                    end >= 0 -> s.indexOf('>', end).let { if (it < 0) s.length else it + 1 }
                    // Ein fehlendes </head> soll nicht den ganzen Text verschlucken.
                    name == "head" -> j
                    else -> s.length
                }
            }
            startTag(name, attributes, selfClosing)
            return j
        }

        /** Wie `s.indexOf("</$name", from, ignoreCase = true)`; [from] wächst von Aufruf zu Aufruf. */
        private fun closingTag(name: String, from: Int): Int {
            val known = closingTags[name]
            if (known != null && (known < 0 || known >= from)) return known
            return s.indexOf("</$name", from, ignoreCase = true).also { closingTags[name] = it }
        }

        private fun startTag(name: String, attributes: Map<String, String>?, selfClosing: Boolean) {
            when {
                name == "br" -> lineBreak()
                name == "hr" -> boundary()
                name in BLOCKS -> openBlock(name, attributes)
                // Tabellenzellen einer Zeile durch ein Leerzeichen trennen.
                name == "td" || name == "th" -> if (lineHasContent) pendingSpace = true
                name in VOID || selfClosing -> Unit
                else -> {
                    val style = attributes?.get("style").orEmpty()
                    val inline = Inline(name, name in BOLD || STYLE_BOLD.containsMatchIn(style), name in ITALIC || STYLE_ITALIC.containsMatchIn(style))
                    inlines += inline
                    if (inline.bold || inline.italic) settleSpace()
                    if (inline.bold) boldCount++
                    if (inline.italic) italicCount++
                }
            }
        }

        private fun endTag(name: String) {
            when {
                name == "br" -> lineBreak()
                name in BLOCKS -> {
                    boundary()
                    val index = blocks.indexOfLast { it.tag == name }
                    if (index >= 0) popBlocks(index)
                }
                else -> {
                    // Nur das passende Element schließen; so bleibt <b><i></b></i> wie im Browser kursiv.
                    val index = inlines.indexOfLast { it.tag == name }
                    if (index < 0) return
                    val inline = inlines.removeAt(index)
                    if (inline.bold || inline.italic) settleSpace()
                    if (inline.bold) boldCount--
                    if (inline.italic) italicCount--
                }
            }
        }

        private fun openBlock(name: String, attributes: Map<String, String>?) {
            boundary()
            // Nicht geschlossene <p>, <li>, <dt>/<dd> und Überschriften enden wie im Browser.
            when (name) {
                "p" -> if (blocks.lastOrNull()?.tag == "p") popBlocks(blocks.lastIndex)
                "li" -> {
                    val index = blocks.indexOfLast { it.tag == "li" || it.tag in LISTS }
                    if (index >= 0 && blocks[index].tag == "li") popBlocks(index)
                }
                "dt", "dd" -> {
                    val index = blocks.indexOfLast { it.tag == "dt" || it.tag == "dd" || it.tag == "dl" }
                    if (index >= 0 && blocks[index].tag != "dl") popBlocks(index)
                }
                in HEADINGS -> if (blocks.lastOrNull()?.tag in HEADINGS) popBlocks(blocks.lastIndex)
            }
            val block = Block(name)
            when (name) {
                "ol" -> block.counter = attributes?.get("start")?.trim()?.toIntOrNull() ?: 1
                "li" -> {
                    val list = blocks.lastOrNull { it.tag in LISTS }
                    block.marker = if (list?.tag == "ol") {
                        attributes?.get("value")?.trim()?.toIntOrNull()?.let { list.counter = it }
                        "${list.counter++}."
                    } else {
                        "•"
                    }
                }
                "pre", "listing" -> {
                    pre++
                    skipNewline = true
                }
            }
            blocks += block
        }

        private fun popBlocks(from: Int) {
            for (k in blocks.lastIndex downTo from) {
                val tag = blocks.removeAt(k).tag
                if (tag == "pre" || tag == "listing") pre--
            }
        }

        /** Zeilenumbruch; ein langer Absatz endet stattdessen und es geht in einem gleichartigen weiter. */
        private fun lineBreak() {
            flushText()
            if (builder.currentBlockLength > TextDocumentBuilder.LONG_BLOCK_CHARS) return boundary()
            builder.lineBreak()
            pendingSpace = false
            lineHasContent = false
        }

        /** Absatzgrenze: Gesammeltes als Absatz in der Art des innersten Blocks abschließen. */
        private fun boundary() {
            flushText()
            if (blockHasText) {
                var style = BlockStyle.Paragraph
                var level = 0
                var marker: String? = null
                for (k in blocks.indices.reversed()) {
                    val block = blocks[k]
                    when (block.tag) {
                        "h1" -> style = BlockStyle.Heading1
                        "h2" -> style = BlockStyle.Heading2
                        "h3", "h4", "h5", "h6" -> style = BlockStyle.Heading3
                        "li" -> {
                            style = BlockStyle.ListItem
                            level = (blocks.subList(0, k).count { it.tag in LISTS } - 1).coerceAtLeast(0)
                            marker = block.marker
                            block.marker = null
                        }
                        "blockquote" -> style = BlockStyle.Quote
                        else -> continue
                    }
                    break
                }
                builder.endBlock(style, marker, level)
            } else {
                builder.endBlock()
            }
            blockHasText = false
            pendingSpace = false
            lineHasContent = false
            skipNewline = false
        }

        /** Vor einem Wechsel zu fett/kursiv gehört ein ausstehendes Leerzeichen noch zum Text davor. */
        private fun settleSpace() {
            if (!pendingSpace) return
            text.append(' ')
            pendingSpace = false
            flushText()
        }

        private fun flushText() {
            if (text.isEmpty()) return
            builder.append(text.toString(), boldCount > 0, italicCount > 0)
            text.setLength(0)
        }
    }
}
