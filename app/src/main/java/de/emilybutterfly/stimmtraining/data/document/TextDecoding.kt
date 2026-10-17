package de.emilybutterfly.stimmtraining.data.document

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Liest Textdateien (TXT, Markdown, HTML) mit unbekannter Kodierung: zuerst die BOM, bei HTML
 * die Angabe im Kopf, sonst striktes UTF-8 und notfalls Windows-1252 – das trifft praktisch
 * alle deutschen Textdateien. Zeilenenden werden zu '\n'.
 */
internal object TextDecoding {
    /** Mehr Text liest niemand am Stück vor; der Rest der Datei bleibt unberücksichtigt. */
    const val MAX_BYTES = 8 * 1024 * 1024

    val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private val META_CHARSET = Regex("""<meta[^>]*?charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)
    private val XML_ENCODING = Regex("""^\s*<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)

    /** [complete] ist false, wenn die Datei länger als [MAX_BYTES] war. */
    class Decoded(val text: String, val complete: Boolean)

    fun read(file: File, html: Boolean = false, limit: Int = MAX_BYTES): Decoded {
        val out = ByteArrayOutputStream(minOf(file.length(), limit.toLong()).toInt().coerceAtLeast(32))
        var complete = true
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() >= limit) {
                    complete = input.read() < 0
                    break
                }
            }
        }
        val bytes = out.toByteArray()
        return Decoded(decode(bytes, complete, if (html) declaredCharset(bytes) else null), complete)
    }

    /** [complete] = false: Am Ende darf eine abgeschnittene UTF-8-Folge stehen. */
    fun decode(bytes: ByteArray, complete: Boolean = true, declared: Charset? = null): String {
        val text = when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            declared != null && declared != Charsets.UTF_8 -> String(bytes, declared)
            else -> strictUtf8(bytes, complete) ?: String(bytes, WINDOWS_1252)
        }
        return normalize(text)
    }

    /** Zeichensatz aus `<meta charset>`, `<meta content="…; charset=…">` oder der XML-Deklaration. */
    fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val name = (META_CHARSET.find(head) ?: XML_ENCODING.find(head))?.groupValues?.get(1) ?: return null
        val charset = try {
            Charset.forName(name)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (charset.name()) {
            // Wie im Browser: Latin-1 und ASCII bedeuten in der Praxis Windows-1252.
            "ISO-8859-1", "US-ASCII" -> WINDOWS_1252
            // Ohne BOM ist eine UTF-16-Angabe im (dann ja einfach lesbaren) Kopf falsch.
            "UTF-16", "UTF-16LE", "UTF-16BE" -> null
            else -> charset
        }
    }

    private fun strictUtf8(bytes: ByteArray, complete: Boolean): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(bytes.size + 1)
        if (decoder.decode(ByteBuffer.wrap(bytes), out, complete).isError) return null
        if (complete && decoder.flush(out).isError) return null
        out.flip()
        return out.toString()
    }

    /** Einheitliche Zeilenenden, ohne BOM und ohne unsichtbare Steuerzeichen. */
    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> {
                    out.append('\n')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == '\n' || c == '\t' -> out.append(c)
                c == '\u000C' -> out.append('\n') // Seitenvorschub
                c < ' ' || c == '\u007F' -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
