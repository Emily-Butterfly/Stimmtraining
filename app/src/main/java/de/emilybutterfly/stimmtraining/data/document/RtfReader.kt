package de.emilybutterfly.stimmtraining.data.document

import de.emilybutterfly.stimmtraining.data.document.StyleNames.isHeading
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * RTF (Word, WordPad, LibreOffice, TextEdit) mit einem kleinen Tokenizer: Gruppen mit
 * Zustandsstapel, Steuerwörter und -symbole. Gelesen werden Absätze, Zeilenumbrüche, fett,
 * kursiv, Sonderzeichen in der Codepage des Dokuments und Unicode (`\uN`). Schriftarten-,
 * Farb- und Listentabellen, Kopfzeilen, Fußnoten, Bilder und Feldbefehle werden übersprungen.
 */
internal object RtfReader {
    /** Mehr Verschachtelung kommt in echten Dateien nicht vor. */
    private const val MAX_DEPTH = 1_000

    /** Langer Text ohne Steuerwort wird spätestens nach so vielen Bytes dekodiert, statt den Puffer wachsen zu lassen. */
    private const val MAX_PENDING_BYTES = 1024 * 1024

    /** Formatvorlagennamen sind kurz; mehr wird nicht gesammelt. */
    private const val MAX_STYLE_NAME = 256

    private val SKIPPED = setOf(
        "colortbl", "info", "pict", "object", "header", "headerl", "headerr", "headerf", "footer", "footerl",
        "footerr", "footerf", "footnote", "fldinst", "listtable", "listoverridetable", "revtbl", "rsidtbl",
        "generator", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "filetbl",
        "pntxta", "pntxtb", "mmathPr", "nonshppict", "annotation", "atnid", "atnauthor", "atndate", "atnref",
        "xe", "tc", "tcn", "pn", "template", "userprops", "docvar", "bkmkstart", "bkmkend", "ftnsep", "ftnsepc",
        "aftnsep", "aftnsepc", "objdata", "pgdsctbl", "protusertbl", "wgrffmtfilter", "listpicture",
    )

    /** Diese Steuerwörter beenden einen Absatz. */
    private val PARAGRAPH_ENDS = setOf("par", "cell", "nestcell", "row", "nestrow", "page", "sect")

    private val SYMBOLS = mapOf(
        "emdash" to "—", "endash" to "–", "bullet" to "•", "lquote" to "‘", "rquote" to "’",
        "ldblquote" to "“", "rdblquote" to "”", "emspace" to "\u2003", "enspace" to "\u2002", "qmspace" to "\u2005",
        "tab" to "\t", "line" to "\n",
    )

    /** \fcharset → Windows-Codepage. */
    private val FONT_CHARSETS = mapOf(
        77 to 10000, 128 to 932, 129 to 949, 130 to 1361, 134 to 936, 136 to 950, 161 to 1253, 162 to 1254,
        163 to 1258, 177 to 1255, 178 to 1256, 186 to 1257, 204 to 1251, 222 to 874, 238 to 1250, 255 to 437,
    )

    fun read(file: File, builder: TextDocumentBuilder, maxPendingBytes: Int = MAX_PENDING_BYTES) {
        file.inputStream().use { Parser(Source(it), builder, maxPendingBytes).run() }
    }

    private fun codepage(number: Int): Charset? {
        val names = when (number) {
            932 -> listOf("windows-31j", "Shift_JIS")
            936 -> listOf("GBK", "GB2312")
            949 -> listOf("x-windows-949", "EUC-KR")
            950 -> listOf("x-windows-950", "Big5")
            10000 -> listOf("x-MacRoman", "MacRoman")
            437, 850 -> listOf("IBM$number", "cp$number")
            65001 -> listOf("UTF-8")
            else -> listOf("windows-$number", "cp$number", "x-windows-$number")
        }
        for (name in names) {
            try {
                return Charset.forName(name)
            } catch (_: IllegalArgumentException) {
                // nächster Name
            }
        }
        return null
    }

    private enum class Destination { Text, Skip, FontTable, StyleSheet }

    private data class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var hidden: Boolean = false,
        var deleted: Boolean = false,
        var destination: Destination = Destination.Text,
        var unicodeSkip: Int = 1,
        var font: Int = -1,
        /** Nummer des gerade beschriebenen Eintrags in Schriftarten- oder Formatvorlagentabelle. */
        var entry: Int = 0,
    )

    /** Gepufferte Bytes mit einem Schritt zurück. */
    private class Source(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var position = 0
        private var length = 0

        fun read(): Int {
            if (position == length) {
                length = input.read(buffer).coerceAtLeast(0)
                position = 0
                if (length == 0) return -1
            }
            return buffer[position++].toInt() and 0xFF
        }

        /** Nur direkt nach einem erfolgreichen [read]. */
        fun unread() {
            position--
        }
    }

    private class Parser(
        private val source: Source,
        private val builder: TextDocumentBuilder,
        private val maxPendingBytes: Int,
    ) {
        private val stack = ArrayList<State>()
        private var state = State()

        /** Text als Bytes in der Codepage; erst zusammen dekodiert, damit Doppelbyte-Zeichen klappen. */
        private val pending = ByteArrayOutputStream()
        private var charset: Charset = TextDecoding.WINDOWS_1252
        private var defaultFont = 0
        private val fontCharsets = HashMap<Int, Int>()
        private val charsets = HashMap<Int, Charset?>()

        private val styleNames = HashMap<Int, String>()
        private val styleOutlines = HashMap<Int, Int>()
        private val styleName = StringBuilder()

        /** Noch zu überspringende Ersatzzeichen nach `\uN`. */
        private var fallback = 0

        private var paragraphStyle = 0
        private var outline = -1
        private var listItem = false
        private var listText = false
        private var listLevel = 0

        fun run() {
            if (source.read() != '{'.code) throw IOException("Kein RTF")
            openGroup()
            while (!builder.isFull) {
                when (val c = source.read()) {
                    -1 -> break
                    '{'.code -> {
                        flush()
                        fallback = 0
                        openGroup()
                    }
                    '}'.code -> {
                        flush()
                        fallback = 0
                        if (!closeGroup()) break
                    }
                    '\\'.code -> control()
                    '\r'.code, '\n'.code -> Unit
                    else -> byte(c)
                }
            }
            flush()
            endParagraph()
        }

        private fun openGroup() {
            if (stack.size >= MAX_DEPTH) throw IOException("RTF zu tief verschachtelt")
            val parent = state
            stack += parent
            state = parent.copy()
            if (parent.destination == Destination.StyleSheet) {
                state.entry = 0
                styleName.setLength(0)
            }
        }

        /** false, wenn die äußerste Gruppe – das Dokument – zu Ende ist. */
        private fun closeGroup(): Boolean {
            if (stack.isEmpty()) return false
            state = stack.removeAt(stack.lastIndex)
            return stack.isNotEmpty()
        }

        private fun byte(c: Int) {
            if (fallback > 0) {
                fallback--
                return
            }
            when (state.destination) {
                Destination.Text -> if (c >= 0x20 || c == '\t'.code) {
                    pending.write(c)
                    if (pending.size() >= maxPendingBytes) flushPart()
                }
                Destination.StyleSheet -> if (c == ';'.code) {
                    styleNames[state.entry] = styleName.toString().trim()
                    styleName.setLength(0)
                } else if (styleName.length < MAX_STYLE_NAME) {
                    styleName.append(c.toChar())
                }
                else -> Unit
            }
        }

        private fun control() {
            val c = source.read()
            if (c < 0) return
            if (isLetter(c)) {
                val word = StringBuilder().append(c.toChar())
                var next = source.read()
                while (isLetter(next) && word.length < 32) {
                    word.append(next.toChar())
                    next = source.read()
                }
                var parameter: Int? = null
                val negative = next == '-'.code
                if (negative) next = source.read()
                if (next in '0'.code..'9'.code) {
                    var value = 0L
                    var digits = 0
                    while (next in '0'.code..'9'.code) {
                        if (digits++ < 10) value = value * 10 + (next - '0'.code)
                        next = source.read()
                    }
                    parameter = (if (negative) -value else value).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                }
                // Ein Leerzeichen nach dem Steuerwort gehört zu ihm, alles andere ist schon Inhalt.
                if (next >= 0 && next != ' '.code) source.unread()
                word(word.toString(), parameter)
            } else {
                symbol(c)
            }
        }

        private fun symbol(c: Int) {
            if (c == '\''.code) {
                val high = Character.digit(source.read(), 16)
                val low = Character.digit(source.read(), 16)
                if (high >= 0 && low >= 0) byte(high * 16 + low)
                return
            }
            if (c == '\\'.code || c == '{'.code || c == '}'.code) return byte(c)
            flush()
            if (fallback > 0) {
                fallback--
                return
            }
            when (c.toChar()) {
                '*' -> state.destination = Destination.Skip
                '~' -> text("\u00A0")
                '_' -> text("-")
                // Ein Backslash vor einem Zeilenende wirkt wie \par.
                '\r', '\n' -> if (state.destination == Destination.Text) endParagraph()
            }
        }

        private fun word(word: String, parameter: Int?) {
            if (word == "bin") return skipBinary(parameter ?: 0)
            flush()
            if (fallback > 0) {
                fallback--
                return
            }
            val destination = state.destination
            if (destination == Destination.Skip) return
            val newDestination = when (word) {
                in SKIPPED -> Destination.Skip
                // Vorab gesetztes Aufzählungszeichen: Der Absatz ist ein Listenpunkt.
                "listtext", "pntext" -> Destination.Skip.also { listText = true }
                "fonttbl" -> Destination.FontTable
                "stylesheet" -> Destination.StyleSheet
                else -> null
            }
            if (newDestination != null) {
                state.destination = newDestination
                return
            }
            when (destination) {
                Destination.FontTable -> when (word) {
                    "f" -> state.entry = parameter ?: 0
                    "fcharset" -> fontCharsets[state.entry] = parameter ?: 0
                }
                Destination.StyleSheet -> when (word) {
                    "s" -> state.entry = parameter ?: 0
                    "outlinelevel" -> styleOutlines[state.entry] = parameter ?: -1
                }
                else -> body(word, parameter)
            }
        }

        private fun body(word: String, parameter: Int?) {
            val on = parameter != 0
            when (word) {
                in PARAGRAPH_ENDS -> endParagraph()
                in SYMBOLS -> text(SYMBOLS.getValue(word))
                "u" -> if (parameter != null) {
                    val code = if (parameter < 0) parameter + 65536 else parameter
                    if (code in 1..0xFFFF) text(code.toChar().toString())
                    fallback = state.unicodeSkip
                }
                "uc" -> state.unicodeSkip = (parameter ?: 1).coerceIn(0, 16)
                "b" -> state.bold = on
                "i" -> state.italic = on
                "v" -> state.hidden = on
                "deleted" -> state.deleted = on
                "plain" -> {
                    state.bold = false
                    state.italic = false
                    state.hidden = false
                    state.deleted = false
                    state.font = defaultFont
                }
                "f" -> state.font = parameter ?: defaultFont
                "deff" -> defaultFont = parameter ?: 0
                "ansicpg" -> parameter?.let(::codepage)?.let { charset = it }
                "mac" -> codepage(10000)?.let { charset = it }
                "pc" -> codepage(437)?.let { charset = it }
                "pca" -> codepage(850)?.let { charset = it }
                "pard" -> {
                    paragraphStyle = 0
                    outline = -1
                    listItem = false
                    listLevel = 0
                }
                "s" -> paragraphStyle = parameter ?: 0
                "outlinelevel" -> outline = parameter ?: -1
                "ls" -> listItem = true
                "ilvl" -> listLevel = parameter ?: 0
            }
        }

        private fun isLetter(c: Int): Boolean = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

        private fun skipBinary(count: Int) {
            repeat(count.coerceAtLeast(0)) { if (source.read() < 0) return }
        }

        private fun text(text: String) {
            if (state.destination != Destination.Text || state.hidden || state.deleted) return
            builder.append(text, state.bold, state.italic)
        }

        /** Gesammelte Bytes mit der Codepage der aktuellen Schrift dekodieren. */
        private fun flush() {
            if (pending.size() == 0) return
            val bytes = pending.toByteArray()
            pending.reset()
            text(String(bytes, fontCharset(state.font) ?: charset))
        }

        /** Wie [flush], aber ein angefangenes Doppelbyte-Zeichen am Ende bleibt im Puffer. */
        private fun flushPart() {
            val bytes = ByteBuffer.wrap(pending.toByteArray())
            pending.reset()
            val decoder = (fontCharset(state.font) ?: charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val chars = CharBuffer.allocate((bytes.remaining() * decoder.maxCharsPerByte()).toInt() + 1)
            decoder.decode(bytes, chars, false)
            pending.write(bytes.array(), bytes.position(), bytes.remaining())
            text(String(chars.array(), 0, chars.position()))
        }

        private fun fontCharset(font: Int): Charset? {
            val number = FONT_CHARSETS[fontCharsets[font] ?: return null] ?: return null
            return charsets.getOrPut(number) { codepage(number) }
        }

        private fun endParagraph() {
            val named = StyleNames.blockStyle(styleNames[paragraphStyle])
                ?: styleOutlines[paragraphStyle]?.takeIf { it in 0..8 }?.let { StyleNames.heading(it + 1) }
            val style = when {
                outline in 0..8 -> StyleNames.heading(outline + 1)
                named != null && named.isHeading -> named
                listItem || listText -> BlockStyle.ListItem
                else -> named ?: BlockStyle.Paragraph
            }
            val item = style == BlockStyle.ListItem
            builder.endBlock(style, if (item) "•" else null, if (item) listLevel else 0)
            listText = false
        }
    }
}
