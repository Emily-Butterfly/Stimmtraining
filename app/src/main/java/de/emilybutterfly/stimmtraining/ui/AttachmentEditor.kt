package de.emilybutterfly.stimmtraining.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import de.emilybutterfly.stimmtraining.data.AttachmentChange
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.PageOrigin
import de.emilybutterfly.stimmtraining.data.pageNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ändert einen gespeicherten Text-Anhang über Entwürfe: ersetzen (Kamera, Galerie, Datei,
 * Einfügen), Fotoseiten hinzufügen, drehen, verschieben, löschen oder alles entfernen. Das
 * Original bleibt bis zum Speichern unangetastet; [discard] räumt die Entwürfe weg.
 *
 * Bei Fotoseiten wird mitgeführt, aus welcher gespeicherten Seite jede Entwurfsseite stammt
 * ([State.origins]), damit ein Lesefortschritt beim Speichern auf seiner Seite bleibt.
 */
class AttachmentEditor(
    private val app: Application,
    private val files: FileStore,
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    /** Der gespeicherte Anhang, solange nichts geändert wurde. */
    private val stored: () -> TextAttachment?,
    private val onMessage: (String) -> Unit,
) {
    data class State(
        val change: AttachmentChange = AttachmentChange.Keep,
        /** Zu jeder Seite eines Foto-Entwurfs: die gespeicherte Seite, aus der sie stammt (null: neu). */
        val origins: List<PageOrigin?> = emptyList(),
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /** Der Anhang, wie er nach dem Speichern aussähe. */
    fun shown(): TextAttachment? = when (val change = _state.value.change) {
        AttachmentChange.Keep -> stored()
        AttachmentChange.Remove -> null
        is AttachmentChange.Replace -> change.attachment
    }

    val changed: Boolean get() = _state.value.change != AttachmentChange.Keep

    fun onCameraPhoto(addPage: Boolean = false) {
        if (addPage) {
            addPages { listOf(files.importCameraCapture(app)) }
        } else {
            replace(isPhoto = true) { TextAttachment.Photo(files.importCameraCapture(app)) }
        }
    }

    fun onPhotosPicked(uris: List<Uri>, addPage: Boolean = false) {
        if (uris.isEmpty()) return
        val limited = uris.take(TextAttachment.MAX_PAGES)
        if (addPage) {
            addPages { files.importDraftPhotos(app, limited) { onMessage(skippedPhotosMessage(it)) } }
        } else {
            replace(isPhoto = true) { DraftPages.append(null, files.importDraftPhotos(app, limited) { onMessage(skippedPhotosMessage(it)) }).first }
        }
        if (uris.size > limited.size) onMessage(TOO_MANY_PAGES)
    }

    fun onDocumentPicked(uri: Uri) = replace(isPhoto = false) { files.importDraftDocument(app, uri) }

    fun onTextPasted(text: String, name: String?) {
        if (text.isBlank()) return
        replace(isPhoto = false) { files.createDraftText(text, name) }
    }

    fun remove() {
        discardDrafts()
        set(State(change = AttachmentChange.Remove))
    }

    fun rotatePage(index: Int, clockwise: Boolean) = changePages(requirePhoto = true) { photo, origins ->
        DraftPages.rotate(files, photo!!, index, clockwise) to
            origins.toMutableList().also { list -> list.getOrNull(index)?.let { list[index] = it.copy(rotated = true) } }
    }

    fun movePage(from: Int, to: Int) = changePages(requirePhoto = true) { photo, origins ->
        val moved = DraftPages.move(photo!!, from, to)
        val list = origins.toMutableList()
        if (moved !== photo) list.add(to, list.removeAt(from))
        moved to list
    }

    fun deletePage(index: Int) = changePages(requirePhoto = true) { photo, origins ->
        DraftPages.delete(photo!!, index) to origins.filterIndexed { i, _ -> i != index }
    }

    /** Verwirft alle Entwürfe, z. B. bei „Abbrechen“. */
    fun discard() {
        job?.cancel()
        discardDrafts()
        set(State())
    }

    /**
     * Nach dem Speichern von [saved]: Dessen Entwürfe gehören jetzt dem Gespeicherten. Kam
     * währenddessen doch noch etwas Neues dazu, wird es verworfen, statt liegen zu bleiben.
     */
    fun committed(saved: State) {
        job?.cancel()
        val current = _state.value
        if (current.change != saved.change) {
            (current.change as? AttachmentChange.Replace)?.attachment?.files
                ?.filter { file -> (saved.change as? AttachmentChange.Replace)?.attachment?.files?.contains(file) != true }
                ?.forEach { it.delete() }
        }
        set(State())
    }

    // --- intern ---

    private fun replace(isPhoto: Boolean, load: suspend () -> TextAttachment) = launch(isPhoto, failure = ::importMessage) {
        val attachment = load()
        discardDrafts()
        State(change = AttachmentChange.Replace(attachment))
    }

    private fun addPages(load: suspend () -> List<File>) = launch(isPhoto = true, failure = ::importMessage) {
        // Erst die gespeicherten Seiten kopieren, dann importieren: Scheitert das Kopieren, ist noch kein neues Foto verloren.
        val (photo, origins) = editablePhoto()
        val added = load()
        if (photo == null) discardDrafts()
        val (result, dropped) = DraftPages.append(photo, added)
        if (dropped > 0) onMessage(TOO_MANY_PAGES)
        State(change = AttachmentChange.Replace(result), origins = (origins + added.map { null }).take(result.pages.size))
    }

    private fun changePages(
        requirePhoto: Boolean,
        change: suspend (TextAttachment.Photo?, List<PageOrigin?>) -> Pair<TextAttachment?, List<PageOrigin?>>,
    ) {
        if (requirePhoto && shown() !is TextAttachment.Photo) return
        launch(isPhoto = false, failure = { _, e -> pageFailureMessage(e) }) {
            val (photo, origins) = editablePhoto()
            if (requirePhoto && photo == null) return@launch null
            val (result, newOrigins) = change(photo, origins)
            State(change = result?.let(AttachmentChange::Replace) ?: AttachmentChange.Remove, origins = if (result == null) emptyList() else newOrigins)
        }
    }

    /**
     * Die Fotoseiten zum Bearbeiten, mit Herkunft. Gespeicherte Seiten werden dafür erst in
     * Entwürfe kopiert.
     */
    private suspend fun editablePhoto(): Pair<TextAttachment.Photo?, List<PageOrigin?>> {
        val current = _state.value
        (current.change as? AttachmentChange.Replace)?.attachment?.let { replaced ->
            val photo = replaced as? TextAttachment.Photo ?: return null to emptyList()
            return photo to current.origins.padded(photo.pages.size)
        }
        if (current.change != AttachmentChange.Keep) return null to emptyList()
        val original = stored() as? TextAttachment.Photo ?: return null to emptyList()
        val copy = DraftPages.copyToDrafts(files, original)
        val origins = original.pages.map { PageOrigin(it.name) }
        set(State(change = AttachmentChange.Replace(copy), origins = origins, busy = true))
        return copy to origins
    }

    /** Führt [work] im Hintergrund aus; null als Ergebnis lässt den Zustand, wie er ist. */
    private fun launch(isPhoto: Boolean, failure: (Boolean, Exception) -> String, work: suspend () -> State?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        job = scope.launch {
            try {
                val result = work()
                if (result == null) _state.update { it.copy(busy = false) } else set(result.copy(busy = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                onMessage(failure(isPhoto, e))
            }
        }
    }

    private fun importMessage(isPhoto: Boolean, e: Exception) = importFailureMessage(isPhoto, e)

    private fun discardDrafts() {
        (_state.value.change as? AttachmentChange.Replace)?.attachment?.files?.forEach { it.delete() }
    }

    private fun set(state: State) {
        _state.value = state
        persist(state)
    }

    private fun persist(state: State) {
        val replacement = (state.change as? AttachmentChange.Replace)?.attachment
        savedState[KEY_MODE] = when (state.change) {
            AttachmentChange.Keep -> null
            AttachmentChange.Remove -> "remove"
            is AttachmentChange.Replace -> "replace"
        }
        savedState[KEY_DRAFTS] = replacement?.files?.joinToString("\n") { it.absolutePath }
        savedState[KEY_NAME] = (replacement as? TextAttachment.Document)?.name
        savedState[KEY_ORIGINS] = state.origins.joinToString("\n") { origin ->
            origin?.let { "${if (it.rotated) "1" else "0"}${it.name}" } ?: "-"
        }.takeIf { state.origins.isNotEmpty() }
    }

    private fun restore(): State {
        val drafts = savedState.get<String>(KEY_DRAFTS).pageNames().mapNotNull(files::ownDraft)
        val name = savedState.get<String>(KEY_NAME)
        val change = when (savedState.get<String>(KEY_MODE)) {
            "remove" -> AttachmentChange.Remove
            "replace" -> drafts.takeIf { it.isNotEmpty() }
                ?.let { if (name != null) TextAttachment.Document(it.first(), name) else TextAttachment.Photo(it) }
                ?.let { AttachmentChange.Replace(it) }
                ?: AttachmentChange.Keep
            else -> AttachmentChange.Keep
        }
        val origins = savedState.get<String>(KEY_ORIGINS).pageNames().map { line ->
            if (line == "-" || line.length < 2) null else PageOrigin(line.substring(1), rotated = line[0] == '1')
        }
        return State(change = change, origins = if (change is AttachmentChange.Replace) origins else emptyList())
    }

    private fun List<PageOrigin?>.padded(size: Int): List<PageOrigin?> = take(size) + List((size - this.size).coerceAtLeast(0)) { null }

    companion object {
        private const val TOO_MANY_PAGES = "Mehr als ${TextAttachment.MAX_PAGES} Seiten passen nicht – die übrigen wurden nicht übernommen."
        private const val KEY_MODE = "attachmentEditor.mode"
        private const val KEY_DRAFTS = "attachmentEditor.drafts"
        private const val KEY_NAME = "attachmentEditor.name"
        private const val KEY_ORIGINS = "attachmentEditor.origins"
    }
}
