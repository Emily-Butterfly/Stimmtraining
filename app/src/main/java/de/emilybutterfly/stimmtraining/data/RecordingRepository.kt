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
        recording.photoPages.isNotEmpty() ->
            TextAttachment.Photo(recording.photoPages.map(files::photo), recording.sectionStartY, recording.sectionEndY)
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
        val stored = draftAttachment?.let(::existing)?.let { storedNames(uuid, it) } ?: StoredAttachment.NONE
        FileStore.move(draftAudio, audioTarget)
        try {
            stored.moveIn()
            dao.insert(
                Recording(
                    uuid = uuid,
                    recordedAt = recordedAt,
                    durationMs = durationMs,
                    audioFile = audioName,
                    photoFile = stored.photoFile,
                    documentFile = stored.documentFile,
                    documentName = stored.documentName,
                    sectionStartY = stored.startY,
                    sectionEndY = stored.endY,
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
            stored.moveBack()
            runCatching { FileStore.move(audioTarget, draftAudio) }
            throw e
        }
    }

    suspend fun update(id: Long, params: RecordingParams, change: AttachmentChange) =
        withContext(Dispatchers.IO) {
            val current = dao.get(id) ?: return@withContext
            val replacement = (change as? AttachmentChange.Replace)?.attachment?.let(::existing)
            val stored = when {
                replacement != null -> storedNames(current.uuid, replacement)
                change == AttachmentChange.Remove -> StoredAttachment.NONE
                // Behalten, oder der Entwurf ist verschwunden: am alten Anhang ändert sich nichts.
                else -> StoredAttachment(
                    emptyList(),
                    current.photoFile,
                    current.documentFile,
                    current.documentName,
                    current.sectionStartY,
                    current.sectionEndY,
                )
            }
            stored.moveIn()
            try {
                dao.updateEdit(
                    RecordingEdit(
                        id = current.id,
                        photoFile = stored.photoFile,
                        documentFile = stored.documentFile,
                        documentName = stored.documentName,
                        sectionStartY = stored.startY,
                        sectionEndY = stored.endY,
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
                stored.moveBack()
                throw e
            }
            val kept = stored.photoFile.pageNames().toSet()
            current.photoPages.filter { it !in kept }.forEach { files.photo(it).delete() }
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
        recording.photoPages.forEach { files.photo(it).delete() }
        recording.documentFile?.let { files.document(it).delete() }
    }

    /** Nur die Dateien, die es noch gibt; null, wenn nichts übrig ist. */
    private fun existing(attachment: TextAttachment): TextAttachment? = when (attachment) {
        is TextAttachment.Photo -> attachment.pages.filter { it.isFile }.let { pages ->
            when {
                pages.isEmpty() -> null
                pages.size == attachment.pages.size -> attachment
                else -> attachment.withPages(pages)
            }
        }
        is TextAttachment.Document -> attachment.takeIf { it.file.isFile }
    }

    /**
     * Wohin ein neuer Anhang dauerhaft kommt: [moves] (Entwurf → Ziel) und die Namen für die Datenbank.
     * [moveIn] verschiebt alles, [moveBack] macht es bei einem Fehler wieder rückgängig.
     */
    private class StoredAttachment(
        val moves: List<Pair<File, File>>,
        val photoFile: String?,
        val documentFile: String?,
        val documentName: String?,
        val startY: Float?,
        val endY: Float?,
    ) {
        fun moveIn() {
            val done = mutableListOf<Pair<File, File>>()
            try {
                for (move in moves) {
                    FileStore.move(move.first, move.second)
                    done += move
                }
            } catch (e: Exception) {
                done.forEach { (source, target) -> runCatching { FileStore.move(target, source) } }
                throw e
            }
        }

        fun moveBack() {
            moves.forEach { (source, target) -> if (target.isFile) runCatching { FileStore.move(target, source) } }
        }

        companion object {
            val NONE = StoredAttachment(emptyList(), null, null, null, null, null)
        }
    }

    private fun storedNames(uuid: String, attachment: TextAttachment): StoredAttachment = when (attachment) {
        is TextAttachment.Photo -> {
            val names = attachment.pages.indices.map { newPhotoName(uuid, it) }
            StoredAttachment(
                moves = attachment.pages.zip(names.map(files::photo)),
                photoFile = names.joinPageNames(),
                documentFile = null,
                documentName = null,
                startY = attachment.startY,
                endY = attachment.endY,
            )
        }
        is TextAttachment.Document -> newDocumentName(uuid, attachment.kind.extension).let {
            StoredAttachment(listOf(attachment.file to files.document(it)), null, it, attachment.name, null, null)
        }
    }

    companion object {
        /** Jedes neue Foto bekommt einen neuen Namen, damit kein altes Bild aus dem Cache erscheint. */
        fun newPhotoName(uuid: String, page: Int = 0) =
            if (page == 0) "${uuid}_${System.currentTimeMillis()}.jpg" else "${uuid}_${System.currentTimeMillis()}_${page + 1}.jpg"

        fun newDocumentName(uuid: String, extension: String) = "${uuid}_${System.currentTimeMillis()}.$extension"
    }
}
