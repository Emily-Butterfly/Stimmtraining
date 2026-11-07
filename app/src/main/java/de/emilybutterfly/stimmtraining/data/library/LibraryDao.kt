package de.emilybutterfly.stimmtraining.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    /** Angeheftete zuerst, dann die neuesten. */
    @Query("SELECT * FROM library_texts ORDER BY pinned DESC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<LibraryText>>

    @Query("SELECT * FROM library_texts WHERE id = :id")
    fun observe(id: Long): Flow<LibraryText?>

    @Query("SELECT * FROM library_texts WHERE id = :id")
    suspend fun get(id: Long): LibraryText?

    @Query("SELECT * FROM library_texts ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<LibraryText>

    @Query("SELECT uuid FROM library_texts")
    suspend fun allUuids(): List<String>

    @Query("SELECT COUNT(*) FROM library_texts")
    suspend fun count(): Int

    /**
     * Ändert sich bei jedem Hinzufügen, Bearbeiten oder Löschen. Die Summe der Änderungszeiten statt
     * des neuesten Werts: Ein Zeitstempel aus der Zukunft (falsche Uhr) würde sonst alles Spätere verdecken.
     */
    @Query("SELECT COUNT(*) || ':' || TOTAL(updatedAt) || ':' || IFNULL(SUM(id), 0) FROM library_texts")
    suspend fun fingerprint(): String

    /** Texte, deren Titel, Schlagworte oder Inhalt [query] enthalten ([query] in Kleinbuchstaben). */
    @Query("SELECT textId FROM library_search WHERE instr(content, :query) > 0")
    fun observeMatches(query: String): Flow<List<Long>>

    @Query("SELECT content FROM library_search WHERE textId = :id")
    suspend fun searchContent(id: Long): String?

    // Gezielte Änderungen: Nur die betroffenen Spalten, damit sich gleichzeitige Änderungen
    // (z. B. Anheften, während die gelesene Stelle gespeichert wird) nicht gegenseitig überschreiben.

    @Query("UPDATE library_texts SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE library_texts SET title = :title, tags = :tags, pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDetails(id: Long, title: String, tags: String, pinned: Boolean, updatedAt: Long)

    @Query(
        "UPDATE library_texts SET progressOffset = :offset, progressAnchor = :anchor, progressPage = :page, progressY = :y, " +
            "progressPageName = :pageName, progressFraction = :fraction, finishedAt = :finishedAt, " +
            "lastReadAt = IFNULL(:lastReadAt, lastReadAt), updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun setProgress(
        id: Long,
        offset: Int?,
        anchor: String?,
        page: Int?,
        y: Float?,
        pageName: String?,
        fraction: Float?,
        finishedAt: Long?,
        lastReadAt: Long?,
        updatedAt: Long,
    )

    @Transaction
    suspend fun setDetailsWithSearch(id: Long, title: String, tags: String, pinned: Boolean, updatedAt: Long, content: String) {
        setDetails(id, title, tags, pinned, updatedAt)
        upsertSearch(LibrarySearch(id, content))
    }

    @Insert
    suspend fun insert(text: LibraryText): Long

    @Update
    suspend fun update(text: LibraryText)

    @Upsert
    suspend fun upsertSearch(search: LibrarySearch)

    @Transaction
    suspend fun insertWithSearch(text: LibraryText, content: String): Long {
        val id = insert(text)
        upsertSearch(LibrarySearch(id, content))
        return id
    }

    /** Text und Suchinhalt zusammen: Nie zeigt die Suche auf einen anderen Stand als die Zeile. */
    @Transaction
    suspend fun updateWithSearch(text: LibraryText, content: String) {
        update(text)
        upsertSearch(LibrarySearch(text.id, content))
    }

    @Query("DELETE FROM library_search WHERE textId = :id")
    suspend fun deleteSearch(id: Long)

    @Query("DELETE FROM library_texts WHERE id = :id")
    suspend fun deleteText(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deleteSearch(id)
        deleteText(id)
    }
}
