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

    /** Ändert sich bei jedem Hinzufügen, Bearbeiten oder Löschen eines Texts. */
    @Query("SELECT COUNT(*) || ':' || IFNULL(MAX(updatedAt), 0) || ':' || IFNULL(SUM(id), 0) FROM library_texts")
    suspend fun fingerprint(): String

    /** Texte, deren Titel, Schlagworte oder Inhalt [query] enthalten ([query] in Kleinbuchstaben). */
    @Query("SELECT textId FROM library_search WHERE instr(content, :query) > 0")
    fun observeMatches(query: String): Flow<List<Long>>

    @Query("SELECT content FROM library_search WHERE textId = :id")
    suspend fun searchContent(id: Long): String?

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
