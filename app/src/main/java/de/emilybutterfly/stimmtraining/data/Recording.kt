package de.emilybutterfly.stimmtraining.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Eine gespeicherte Stimmaufnahme mit ihren Parametern.
 *
 * [audioFile], [photoFile] und [documentFile] sind reine Dateinamen in [FileStore.audioDir],
 * [FileStore.photoDir] bzw. [FileStore.documentDir]. Der gelesene Text hängt entweder als Fotos
 * oder als Datei (PDF, Word, …) an; [documentName] ist der ursprüngliche Name der Datei.
 * [photoFile] enthält bei mehreren Seiten einen Namen pro Zeile (siehe [photoPages]); so lesen
 * auch ältere Datenbanken und Sicherungen mit genau einem Foto richtig.
 * [uuid] identifiziert die Aufnahme geräteübergreifend, damit beim Wiederherstellen eines
 * Backups nichts doppelt importiert wird.
 */
@Entity(tableName = "recordings", indices = [Index(value = ["uuid"], unique = true)])
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    /** Datum und Uhrzeit des Aufnahmebeginns (Epoch-Millisekunden). */
    val recordedAt: Long,
    val durationMs: Long,
    val audioFile: String,
    val photoFile: String?,
    val documentFile: String? = null,
    val documentName: String? = null,
    /**
     * Gelesener Abschnitt auf den Fotoseiten (0 = oben, 1 = unten): ab [sectionStartY] auf der ersten
     * und bis [sectionEndY] auf der letzten Seite; null heißt ganze Seite. Gesetzt, wenn nur ein Teil
     * eines Bibliothekstexts gelesen wurde.
     */
    val sectionStartY: Float? = null,
    val sectionEndY: Float? = null,
    /**
     * Ergebnis der Tonhöhen-Analyse als MIDI-Noten (A4 = 69). [pitchVoicedMs] ist null, solange
     * nicht analysiert wurde; die Noten sind null, wenn zu wenig Stimme erkannt wurde.
     */
    val pitchMostFrequent: Int? = null,
    val pitchLowest: Int? = null,
    val pitchHighest: Int? = null,
    val pitchVoicedMs: Long? = null,
    val title: String,
    val exercise: String,
    val warmedUp: Boolean,
    val warmUpMinutes: Int?,
    /** Stimmliche Tagesform, 1 (schlecht) bis 5 (sehr gut). */
    val voiceCondition: Int?,
    /** Zufriedenheit mit der Aufnahme, 1 bis 5. */
    val selfRating: Int?,
    val notes: String,
    val updatedAt: Long,
) {
    val displayTitle: String
        get() = title.ifBlank { exercise.ifBlank { "Aufnahme" } }

    /** Die Fotoseiten in Lesereihenfolge. */
    val photoPages: List<String> get() = photoFile.pageNames()

    companion object {
        /**
         * Höchstlängen der Textfelder. Eine Datenbankzeile muss in Androids Lesefenster (2 MB)
         * passen; sonst ließe sich keine Aufnahme mehr anzeigen – auch nicht nach einem Backup.
         */
        const val MAX_TITLE = 200
        const val MAX_EXERCISE = 200
        const val MAX_NOTES = 20_000

        /** Ohne Steuerzeichen (Zeilenumbrüche und Tabs bleiben), gekürzt auf [max] Zeichen. */
        fun cleanText(raw: String, max: Int): String =
            raw.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "").trim().takeWhole(max)
    }

    fun params() = RecordingParams(
        title = title,
        exercise = exercise,
        warmedUp = warmedUp,
        warmUpMinutes = warmUpMinutes?.toString() ?: "",
        voiceCondition = voiceCondition,
        selfRating = selfRating,
        notes = notes,
    )
}

/** Die vom Menschen eingegebenen Parameter einer Aufnahme, wie im Formular bearbeitet. */
data class RecordingParams(
    val title: String = "",
    val exercise: String = "",
    val warmedUp: Boolean = false,
    /** Als Text gehalten, damit das Eingabefeld auch leer sein kann. */
    val warmUpMinutes: String = "",
    val voiceCondition: Int? = null,
    val selfRating: Int? = null,
    val notes: String = "",
) {
    val warmUpMinutesOrNull: Int?
        get() = if (warmedUp) warmUpMinutes.trim().toIntOrNull()?.takeIf { it in 0..600 } else null
}

/**
 * Was sich in der Detailansicht bearbeiten lässt. Als Teil-Update geschrieben, damit ein
 * gleichzeitig gespeichertes Ergebnis der Tonhöhen-Analyse nicht überschrieben wird.
 */
data class RecordingEdit(
    val id: Long,
    val photoFile: String?,
    val documentFile: String?,
    val documentName: String?,
    val sectionStartY: Float?,
    val sectionEndY: Float?,
    val title: String,
    val exercise: String,
    val warmedUp: Boolean,
    val warmUpMinutes: Int?,
    val voiceCondition: Int?,
    val selfRating: Int?,
    val notes: String,
    val updatedAt: Long,
)
