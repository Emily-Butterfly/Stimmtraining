package de.emilybutterfly.stimmtraining.data

import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import java.io.File

/** Der gelesene Text zu einer Aufnahme: ein Foto oder eine Datei (PDF, Word, Text, …). */
sealed interface TextAttachment {
    val file: File

    data class Photo(override val file: File) : TextAttachment

    /** [name] ist der ursprüngliche Dateiname, z. B. „Nordwind und Sonne.docx“. */
    data class Document(override val file: File, val name: String) : TextAttachment {
        /** Das Format steckt in der Endung, unter der die App die Datei ablegt. */
        val kind: DocumentKind get() = DocumentKind.fromExtension(file.extension) ?: DocumentKind.PlainText
    }
}
