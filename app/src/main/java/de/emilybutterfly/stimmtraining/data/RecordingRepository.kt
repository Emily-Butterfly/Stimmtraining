package de.emilybutterfly.stimmtraining.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

    /**
     * Übernimmt die Entwurfsdateien dauerhaft und legt die Aufnahme an. Schlägt etwas fehl,
     * liegen die Entwürfe danach wieder an ihrem alten Platz, sodass erneutes Speichern geht.
     */
    suspend fun create(
        draftAudio: File,
        draftPhoto: File?,
        recordedAt: Long,
        durationMs: Long,
        params: RecordingParams,
    ): Long = withContext(Dispatchers.IO) {
        if (!draftAudio.isFile) throw IOException("Die Aufnahme-Datei ist nicht mehr vorhanden.")
        val uuid = UUID.randomUUID().toString()
        val audioName = "$uuid.m4a"
        val audioTarget = files.audio(audioName)
        // Ein verschwundener Foto-Entwurf soll nicht die ganze Aufnahme kosten.
        val photo = draftPhoto?.takeIf { it.isFile }
        val photoName = photo?.let { newPhotoName(uuid) }
        val photoTarget = photoName?.let(files::photo)
        FileStore.move(draftAudio, audioTarget)
        try {
            if (photo != null && photoTarget != null) FileStore.move(photo, photoTarget)
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
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            if (photo != null && photoTarget != null && photoTarget.isFile) runCatching { FileStore.move(photoTarget, photo) }
            runCatching { FileStore.move(audioTarget, draftAudio) }
            throw e
        }
    }

    suspend fun update(id: Long, params: RecordingParams, photoChange: PhotoChange) =
        withContext(Dispatchers.IO) {
            val current = dao.get(id) ?: return@withContext
            val replacement = (photoChange as? PhotoChange.Replace)?.draft?.takeIf { it.isFile }
            val photoName = when (photoChange) {
                PhotoChange.Keep -> current.photoFile
                PhotoChange.Remove -> null
                is PhotoChange.Replace -> if (replacement != null) newPhotoName(current.uuid) else current.photoFile
            }
            val replacementTarget = if (replacement != null && photoName != null) files.photo(photoName) else null
            if (replacement != null && replacementTarget != null) FileStore.move(replacement, replacementTarget)
            try {
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
            } catch (e: Exception) {
                if (replacement != null && replacementTarget != null) runCatching { FileStore.move(replacementTarget, replacement) }
                throw e
            }
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
