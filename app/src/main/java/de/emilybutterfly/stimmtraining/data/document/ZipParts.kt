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
 * Liest XML-Teile aus ZIP-basierten Dokumenten (DOCX, ODT). Die entpackte Größe und die
 * Verschachtelungstiefe sind begrenzt, damit eine präparierte Datei („ZIP-Bombe“, ein riesiger
 * Textknoten, endlos verschachtelte Elemente) nicht den Speicher füllt.
 */
internal object ZipParts {
    /** Ein einzelner Textknoten wird vom Parser am Stück gepuffert; mehr verträgt ein schwaches Gerät nicht sicher. */
    const val MAX_BYTES = 16L * 1024 * 1024

    /** Echte Dokumente kommen auch mit Tabellen in Textfeldern mit einem Bruchteil davon aus. */
    const val MAX_DEPTH = 256

    fun open(zip: ZipFile, name: String, maxBytes: Long): InputStream? {
        val entry = zip.getEntry(name) ?: return null
        return LimitedInputStream(zip.getInputStream(entry), maxBytes)
    }

    /**
     * Parser mit Namensräumen, positioniert auf dem Wurzelelement. Elemente tiefer als [MAX_DEPTH]
     * beenden das Lesen mit einer [XmlPullParserException] – in jeder Leseschleife, auch beim Überspringen.
     */
    fun parser(input: InputStream): XmlPullParser {
        val parser: XmlPullParser = DepthLimitedParser(Xml.newPullParser())
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

    private class DepthLimitedParser(private val parser: XmlPullParser) : XmlPullParser by parser {
        override fun next(): Int = checked(parser.next())
        override fun nextToken(): Int = checked(parser.nextToken())
        override fun nextTag(): Int = checked(parser.nextTag())

        private fun checked(event: Int): Int {
            if (event == XmlPullParser.START_TAG && parser.depth > MAX_DEPTH) throw XmlPullParserException("zu tief verschachtelt")
            return event
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
