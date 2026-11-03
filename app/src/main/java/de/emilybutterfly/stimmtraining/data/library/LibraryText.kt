package de.emilybutterfly.stimmtraining.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.emilybutterfly.stimmtraining.data.pageNames

/**
 * Ein Text in der Bibliothek: Fotos oder eine Datei, die man über mehrere Aufnahmen hinweg liest.
 *
 * Die Dateien liegen in [FileStore.libraryDir][de.emilybutterfly.stimmtraining.data.FileStore.libraryDir]:
 * [photoFiles] (ein Name pro Zeile) oder [documentFile] mit dem ursprünglichen Namen [documentName].
 * Ist [finishedAt] gesetzt, steht der Text unter „Gelesen“, sonst unter „Zu lesen“.
 */
@Entity(tableName = "library_texts", indices = [Index(value = ["uuid"], unique = true)])
data class LibraryText(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val title: String,
    /** Schlagworte, eines pro Zeile (siehe [tagList]). */
    val tags: String = "",
    val photoFiles: String? = null,
    val documentFile: String? = null,
    val documentName: String? = null,
    /** Angeheftete Texte stehen oben. */
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Zu Ende gelesen am (Epoch-Millisekunden). */
    val finishedAt: Long? = null,
    val lastReadAt: Long? = null,
    /** Fortschritt in einem Text (siehe [ReadingPosition.InText]). */
    val progressOffset: Int? = null,
    val progressAnchor: String? = null,
    /** Fortschritt auf Seiten (siehe [ReadingPosition.OnPage]). */
    val progressPage: Int? = null,
    val progressY: Float? = null,
    val progressPageName: String? = null,
    /** Gelesener Anteil (0..1), für die Anzeige in der Liste. */
    val progressFraction: Float? = null,
) {
    val photoPages: List<String> get() = photoFiles.pageNames()

    val tagList: List<String> get() = tags.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    val isFinished: Boolean get() = finishedAt != null

    /** Bis wohin gelesen ist; null: noch nicht begonnen (oder zu Ende gelesen). */
    val progress: ReadingPosition?
        get() = when {
            progressOffset != null && progressAnchor != null -> ReadingPosition.InText(progressOffset, progressAnchor)
            progressPage != null && progressY != null -> ReadingPosition.OnPage(progressPage, progressY, progressPageName)
            else -> null
        }

    /** Derselbe Text mit neuem Fortschritt; null setzt ihn auf den Anfang zurück. */
    fun withProgress(position: ReadingPosition?, fraction: Float?): LibraryText = copy(
        progressOffset = (position as? ReadingPosition.InText)?.offset,
        progressAnchor = (position as? ReadingPosition.InText)?.anchor,
        progressPage = (position as? ReadingPosition.OnPage)?.page,
        progressY = (position as? ReadingPosition.OnPage)?.y,
        progressPageName = (position as? ReadingPosition.OnPage)?.pageName,
        progressFraction = fraction?.takeIf { position != null }?.coerceIn(0f, 1f),
    )

    companion object {
        /** Bereinigt eingegebene Schlagworte: ohne Leerraum am Rand, ohne Doppelte (Groß-/Kleinschreibung egal). */
        fun joinTags(tags: List<String>): String =
            tags.map { it.replace(Regex("\\s+"), " ").trim().take(MAX_TAG_LENGTH) }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString("\n")

        const val MAX_TAG_LENGTH = 40
    }
}

/** Durchsuchbarer Inhalt eines Bibliothekstexts: Titel, Schlagworte und Text in Kleinbuchstaben. */
@Entity(
    tableName = "library_search",
    foreignKeys = [
        ForeignKey(entity = LibraryText::class, parentColumns = ["id"], childColumns = ["textId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LibrarySearch(
    @PrimaryKey val textId: Long,
    val content: String,
)
