package de.emilybutterfly.stimmtraining.data.document

import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Liest den Text eines Dokuments (alle Formate außer PDF). */
object DocumentText {
    /**
     * @throws IOException wenn die Datei beschädigt ist oder sich nicht lesen lässt; die Meldung
     *         ist auf Deutsch und kann so angezeigt werden.
     * @throws IllegalArgumentException für [DocumentKind.Pdf] – PDFs werden als Seiten angezeigt.
     */
    fun read(file: File, kind: DocumentKind, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument {
        require(kind != DocumentKind.Pdf) { "PDF-Dateien werden als Seiten angezeigt, nicht als Text gelesen" }
        val builder = TextDocumentBuilder(maxChars)
        var complete = true
        try {
            when (kind) {
                DocumentKind.Docx -> DocxReader.read(file, builder)
                DocumentKind.Odt -> OdtReader.read(file, builder)
                DocumentKind.Rtf -> RtfReader.read(file, builder)
                DocumentKind.Html, DocumentKind.Markdown, DocumentKind.PlainText -> {
                    val decoded = TextDecoding.read(file, html = kind == DocumentKind.Html)
                    complete = decoded.complete
                    when (kind) {
                        DocumentKind.Html -> HtmlReader.read(decoded.text, builder)
                        DocumentKind.Markdown -> MarkdownReader.read(decoded.text, builder)
                        else -> PlainTextReader.read(decoded.text, builder)
                    }
                }
                DocumentKind.Pdf -> Unit
            }
        } catch (e: DocumentReadException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw DocumentReadException("Die Datei konnte nicht geöffnet werden.", e)
        } catch (e: IOException) {
            throw DocumentReadException(damaged(kind), e)
        } catch (e: XmlPullParserException) {
            throw DocumentReadException(damaged(kind), e)
        } catch (e: RuntimeException) {
            throw DocumentReadException(damaged(kind), e)
        } catch (e: OutOfMemoryError) {
            // Letzte Sicherung, falls eine Datei trotz aller Grenzen zu viel Speicher braucht: Meldung statt Absturz.
            throw DocumentReadException("Das Dokument ist zu groß, um es als Text anzuzeigen.", e)
        }
        val document = builder.build()
        return if (complete) document else document.copy(truncated = true)
    }

    private fun damaged(kind: DocumentKind): String = "Die Datei ist beschädigt oder " + when (kind) {
        DocumentKind.Docx -> "kein gültiges Word-Dokument."
        DocumentKind.Odt -> "kein gültiges OpenDocument-Dokument."
        DocumentKind.Rtf -> "kein gültiges RTF-Dokument."
        DocumentKind.Html -> "keine gültige HTML-Datei."
        DocumentKind.Markdown -> "keine gültige Markdown-Datei."
        DocumentKind.PlainText, DocumentKind.Pdf -> "keine gültige Textdatei."
    }
}
