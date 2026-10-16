package de.emilybutterfly.stimmtraining.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY recordedAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Query("SELECT * FROM recordings ORDER BY recordedAt ASC")
    suspend fun getAll(): List<Recording>

    @Query("SELECT uuid FROM recordings")
    suspend fun allUuids(): List<String>

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int

    /** Ändert sich bei jedem Hinzufügen, Bearbeiten oder Löschen einer Aufnahme. */
    @Query("SELECT COUNT(*) || ':' || IFNULL(MAX(updatedAt), 0) || ':' || IFNULL(SUM(id), 0) FROM recordings")
    suspend fun fingerprint(): String

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)
}
