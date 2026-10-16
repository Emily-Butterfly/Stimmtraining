package de.emilybutterfly.stimmtraining.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Ändert an einer gespeicherten Aufnahme das Foto: behalten, entfernen oder ersetzen. */
sealed interface PhotoChange {
    data object Keep : PhotoChange
    data object Remove : PhotoChange
    data class Replace(val draft: File) : PhotoChange
}

class RecordingRepository(
    private val dao: RecordingDao,
    private val files: FileStore,
) {
    fun observeAll(): Flow<List<Recording>> = dao.observeAll()

    fun observe(id: Long): Flow<Recording?> = dao.observe(id)

    /** Übernimmt die Entwurfsdateien dauerhaft und legt die Aufnahme an. */
    suspend fun create(
        draftAudio: File,
        draftPhoto: File?,
        recordedAt: Long,
        durationMs: Long,
        params: RecordingParams,
    ): Long = withContext(Dispatchers.IO) {
        val uuid = UUID.randomUUID().toString()
        val audioName = "$uuid.m4a"
        FileStore.move(draftAudio, files.audio(audioName))
        val photoName = draftPhoto?.let { photo ->
            newPhotoName(uuid).also { FileStore.move(photo, files.photo(it)) }
        }
        val now = System.currentTimeMillis()
        dao.insert(
            Recording(
                uuid = uuid,
                recordedAt = recordedAt,
                durationMs = durationMs,
                audioFile = audioName,
                photoFile = photoName,
                title = params.title.trim(),
                exercise = params.exercise.trim(),
                warmedUp = params.warmedUp,
                warmUpMinutes = params.warmUpMinutesOrNull,
                voiceCondition = params.voiceCondition,
                selfRating = params.selfRating,
                notes = params.notes.trim(),
                updatedAt = now,
            ),
        )
    }

    suspend fun update(id: Long, params: RecordingParams, photoChange: PhotoChange) =
        withContext(Dispatchers.IO) {
            val current = dao.get(id) ?: return@withContext
            val photoName = when (photoChange) {
                PhotoChange.Keep -> current.photoFile
                PhotoChange.Remove -> null
                is PhotoChange.Replace -> newPhotoName(current.uuid).also {
                    FileStore.move(photoChange.draft, files.photo(it))
                }
            }
            dao.update(
                current.copy(
                    photoFile = photoName,
                    title = params.title.trim(),
                    exercise = params.exercise.trim(),
                    warmedUp = params.warmedUp,
                    warmUpMinutes = params.warmUpMinutesOrNull,
                    voiceCondition = params.voiceCondition,
                    selfRating = params.selfRating,
                    notes = params.notes.trim(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            if (current.photoFile != null && current.photoFile != photoName) {
                files.photo(current.photoFile).delete()
            }
        }

    suspend fun delete(recording: Recording) = withContext(Dispatchers.IO) {
        dao.delete(recording)
        files.audio(recording.audioFile).delete()
        recording.photoFile?.let { files.photo(it).delete() }
    }

    companion object {
        /** Jedes neue Foto bekommt einen neuen Namen, damit kein altes Bild aus dem Cache erscheint. */
        fun newPhotoName(uuid: String) = "${uuid}_${System.currentTimeMillis()}.jpg"
    }
}
