package de.emilybutterfly.stimmtraining.data.document

import java.io.IOException

/**
 * Dateiformate, die sich als Text zu einer Aufnahme hinterlegen lassen. [extension] ist die
 * Endung, unter der die Datei in der App (und in Sicherungen) abgelegt wird.
 */
enum class DocumentKind(val extension: String, val mimeType: String, val label: String) {
    Pdf("pdf", "application/pdf", "PDF"),
    Docx("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word-Dokument"),
    Odt("odt", "application/vnd.oasis.opendocument.text", "OpenDocument-Text"),
    Rtf("rtf", "application/rtf", "RTF-Dokument"),
    Html("html", "text/html", "Webseite"),
    Markdown("md", "text/markdown", "Markdown"),
    PlainText("txt", "text/plain", "Textdatei"),
    ;

    companion object {
        fun fromExtension(extension: String): DocumentKind? =
            entries.firstOrNull { it.extension == extension.lowercase() }

        /**
         * Für die Dateiauswahl. "application/octet-stream" ist dabei, weil viele Dateimanager
         * z. B. Markdown-Dateien so melden; das tatsächliche Format erkennt der Import am Inhalt.
         * Alte Word-Dateien (.doc) sind wählbar, damit eine verständliche Meldung kommt statt
         * einer ausgegrauten Datei.
         */
        val PICKER_MIME_TYPES = arrayOf(
            "application/pdf",
            "text/*",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "application/x-rtf",
            "application/xhtml+xml",
            "application/msword",
            "application/octet-stream",
        )
    }
}

/** Die Datei hat ein Format, das die App nicht lesen kann; [message] erklärt es auf Deutsch. */
class UnsupportedDocumentException(message: String) : IOException(message)
