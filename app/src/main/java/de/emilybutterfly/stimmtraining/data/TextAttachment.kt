package de.emilybutterfly.stimmtraining.data

import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import java.io.File

/** Der gelesene Text zu einer Aufnahme: Fotos oder eine Datei (PDF, Word, Text, …). */
sealed interface TextAttachment {
    /** Alle Dateien, die zum Anhang gehören. */
    val files: List<File>

    /**
     * Eine oder mehrere fotografierte Seiten, in Lesereihenfolge. [startY] und [endY] markieren
     * den gelesenen Abschnitt (0 = oben, 1 = unten): Er beginnt auf der ersten Seite bei [startY]
     * und endet auf der letzten bei [endY]; null heißt ganze Seite.
     */
    data class Photo(val pages: List<File>, val startY: Float? = null, val endY: Float? = null) : TextAttachment {
        constructor(page: File) : this(listOf(page))

        override val files: List<File> get() = pages

        /** Dieselben Seiten ohne Abschnittslinien, z. B. nachdem Seiten geändert wurden. */
        fun withPages(pages: List<File>) = Photo(pages)
    }

    /** [name] ist der ursprüngliche Dateiname, z. B. „Nordwind und Sonne.docx“. */
    data class Document(val file: File, val name: String) : TextAttachment {
        override val files: List<File> get() = listOf(file)

        /** Das Format steckt in der Endung, unter der die App die Datei ablegt. */
        val kind: DocumentKind get() = DocumentKind.fromExtension(file.extension) ?: DocumentKind.PlainText
    }

    companion object {
        /** So viele Seiten passen in einen Anhang; ein Foto braucht etwa 0,5–1 MB. */
        const val MAX_PAGES = 30
    }
}

/** Die Dateinamen von Fotoseiten, wie sie in der Datenbank stehen (eine pro Zeile). */
fun String?.pageNames(): List<String> = this?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

/** Gegenstück zu [pageNames]; null, wenn es keine Seiten gibt. */
fun List<String>.joinPageNames(): String? = takeIf { it.isNotEmpty() }?.joinToString("\n")
