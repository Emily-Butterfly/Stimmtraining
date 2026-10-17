package de.emilybutterfly.stimmtraining.data

import de.emilybutterfly.stimmtraining.analysis.PitchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Ändert an einer gespeicherten Aufnahme den Text-Anhang: behalten, entfernen oder ersetzen. */
sealed interface AttachmentChange {
    data object Keep : AttachmentChange
    data object Remove : AttachmentChange

    /** [attachment] verweist auf Entwurfsdateien; ein Foto ersetzt ein Dokument und umgekehrt. */
    data class Replace(val attachment: TextAttachment) : AttachmentChange
}

class RecordingRepository(
    private val dao: RecordingDao,
    private val files: FileStore,
) {
    fun observeAll(): Flow<List<Recording>> = dao.observeAll()

    fun observe(id: Long): Flow<Recording?> = dao.observe(id)

    /** Der Text-Anhang einer gespeicherten Aufnahme. */
    fun attachmentOf(recording: Recording): TextAttachment? = when {
        recording.documentFile != null ->
            TextAttachment.Document(files.document(recording.documentFile), recording.documentName ?: recording.documentFile)
        recording.photoFile != null -> TextAttachment.Photo(files.photo(recording.photoFile))
        else -> null
    }

    /**
     * Übernimmt die Entwurfsdateien dauerhaft und legt die Aufnahme an. Schlägt etwas fehl,
     * liegen die Entwürfe danach wieder an ihrem alten Platz, sodass erneutes Speichern geht.
     */
    suspend fun create(
        draftAudio: File,
        draftAttachment: TextAttachment?,
        recordedAt: Long,
        durationMs: Long,
        params: RecordingParams,
        pitch: PitchResult? = null,
    ): Long = withContext(Dispatchers.IO) {
        if (!draftAudio.isFile) throw IOException("Die Aufnahme-Datei ist nicht mehr vorhanden.")
        val uuid = UUID.randomUUID().toString()
        val audioName = "$uuid.m4a"
        val audioTarget = files.audio(audioName)
        // Ein verschwundener Anhang-Entwurf soll nicht die ganze Aufnahme kosten.
        val attachment = draftAttachment?.takeIf { it.file.isFile }
        val stored = attachment?.let { storedNames(uuid, it) }
        val attachmentTarget = stored?.target
        FileStore.move(draftAudio, audioTarget)
        try {
            if (attachment != null && attachmentTarget != null) FileStore.move(attachment.file, attachmentTarget)
            dao.insert(
                Recording(
                    uuid = uuid,
                    recordedAt = recordedAt,
                    durationMs = durationMs,
                    audioFile = audioName,
                    photoFile = stored?.photoFile,
                    documentFile = stored?.documentFile,
                    documentName = stored?.documentName,
                    title = params.title.trim(),
                    exercise = params.exercise.trim(),
                    warmedUp = params.warmedUp,
                    warmUpMinutes = params.warmUpMinutesOrNull,
                    voiceCondition = params.voiceCondition,
                    selfRating = params.selfRating,
                    notes = params.notes.trim(),
                    pitchMostFrequent = pitch?.mostFrequent,
                    pitchLowest = pitch?.lowest,
                    pitchHighest = pitch?.highest,
                    pitchVoicedMs = pitch?.voicedMs,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            if (attachment != null && attachmentTarget != null && attachmentTarget.isFile) {
                runCatching { FileStore.move(attachmentTarget, attachment.file) }
            }
            runCatching { FileStore.move(audioTarget, draftAudio) }
            throw e
        }
    }

    suspend fun update(id: Long, params: RecordingParams, change: AttachmentChange) =
        withContext(Dispatchers.IO) {
            val current = dao.get(id) ?: return@withContext
            val replacement = (change as? AttachmentChange.Replace)?.attachment?.takeIf { it.file.isFile }
            val stored = when {
                replacement != null -> storedNames(current.uuid, replacement)
                change == AttachmentChange.Remove -> StoredAttachment(null, null, null, null)
                // Behalten, oder der Entwurf ist verschwunden: am alten Anhang ändert sich nichts.
                else -> StoredAttachment(null, current.photoFile, current.documentFile, current.documentName)
            }
            if (replacement != null && stored.target != null) FileStore.move(replacement.file, stored.target)
            try {
                dao.updateEdit(
                    RecordingEdit(
                        id = current.id,
                        photoFile = stored.photoFile,
                        documentFile = stored.documentFile,
                        documentName = stored.documentName,
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
                if (replacement != null && stored.target != null) runCatching { FileStore.move(stored.target, replacement.file) }
                throw e
            }
            if (current.photoFile != null && current.photoFile != stored.photoFile) files.photo(current.photoFile).delete()
            if (current.documentFile != null && current.documentFile != stored.documentFile) {
                files.document(current.documentFile).delete()
            }
        }

    /** Speichert das Ergebnis der Tonhöhen-Analyse (zählt als Änderung, damit es ins nächste Backup kommt). */
    suspend fun savePitch(id: Long, result: PitchResult) = withContext(Dispatchers.IO) {
        dao.updatePitch(id, result.mostFrequent, result.lowest, result.highest, result.voicedMs, System.currentTimeMillis())
    }

    suspend fun delete(recording: Recording) = withContext(Dispatchers.IO) {
        dao.delete(recording)
        files.audio(recording.audioFile).delete()
        recording.photoFile?.let { files.photo(it).delete() }
        recording.documentFile?.let { files.document(it).delete() }
    }

    /** Dateinamen, unter denen ein neuer Anhang dauerhaft abgelegt wird. */
    private class StoredAttachment(
        val target: File?,
        val photoFile: String?,
        val documentFile: String?,
        val documentName: String?,
    )

    private fun storedNames(uuid: String, attachment: TextAttachment): StoredAttachment = when (attachment) {
        is TextAttachment.Photo -> newPhotoName(uuid).let { StoredAttachment(files.photo(it), it, null, null) }
        is TextAttachment.Document -> newDocumentName(uuid, attachment.kind.extension).let {
            StoredAttachment(files.document(it), null, it, attachment.name)
        }
    }

    companion object {
        /** Jedes neue Foto bekommt einen neuen Namen, damit kein altes Bild aus dem Cache erscheint. */
        fun newPhotoName(uuid: String) = "${uuid}_${System.currentTimeMillis()}.jpg"

        fun newDocumentName(uuid: String, extension: String) = "${uuid}_${System.currentTimeMillis()}.$extension"
    }
}
