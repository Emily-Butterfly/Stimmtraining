package de.emilybutterfly.stimmtraining.data.document

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Ein Fehler, dessen Meldung schon für die Nutzerin formuliert ist. */
internal class DocumentReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Liest XML-Teile aus ZIP-basierten Dokumenten (DOCX, ODT). Die entpackte Größe ist begrenzt,
 * damit eine präparierte Datei („ZIP-Bombe“) nicht den Speicher füllt.
 */
internal object ZipParts {
    const val MAX_BYTES = 64L * 1024 * 1024

    fun open(zip: ZipFile, name: String, maxBytes: Long): InputStream? {
        val entry = zip.getEntry(name) ?: return null
        return LimitedInputStream(zip.getInputStream(entry), maxBytes)
    }

    /** Parser mit Namensräumen, positioniert auf dem Wurzelelement. */
    fun parser(input: InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.START_TAG -> return parser
                // Office-Dateien haben nie eine DOCTYPE; mit ihr ließen sich Entitäten aufblähen.
                XmlPullParser.DOCDECL -> throw XmlPullParserException("DOCTYPE nicht erlaubt")
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Leeres XML")
            }
        }
    }

    /** Überspringt das aktuelle Element samt Inhalt; danach steht der Parser auf dessen END_TAG. */
    fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (parser.depth == depth) return
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unerwartetes Ende")
            }
        }
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count(skipped)
            return skipped
        }

        private fun count(n: Long) {
            count += n
            if (count > limit) throw DocumentReadException("Das Dokument ist zu groß, um es als Text anzuzeigen.")
        }
    }
}
