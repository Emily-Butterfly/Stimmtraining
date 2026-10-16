package de.emilybutterfly.stimmtraining.ui.detail

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.data.AttachmentChange
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.getParams
import de.emilybutterfly.stimmtraining.ui.importCameraCapture
import de.emilybutterfly.stimmtraining.ui.importDraftDocument
import de.emilybutterfly.stimmtraining.ui.importDraftPhoto
import de.emilybutterfly.stimmtraining.ui.openWithChooser
import de.emilybutterfly.stimmtraining.ui.prepareCameraCapture
import de.emilybutterfly.stimmtraining.ui.putParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface DetailLoad {
    data object Loading : DetailLoad
    data object Missing : DetailLoad
    data class Ready(val recording: Recording) : DetailLoad
}

data class EditState(
    val params: RecordingParams,
    val attachmentChange: AttachmentChange = AttachmentChange.Keep,
    val attachmentBusy: Boolean = false,
    val saving: Boolean = false,
)

class DetailViewModel(
    private val id: Long,
    private val app: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val files = container.files

    val load: StateFlow<DetailLoad> = container.repository.observe(id)
        .map { if (it == null) DetailLoad.Missing else DetailLoad.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLoad.Loading)

    private val _edit = MutableStateFlow(restoreEdit())
    val edit: StateFlow<EditState?> = _edit.asStateFlow()

    init {
        // Bearbeitungsstand sichern, damit er überlebt, wenn Android die App beendet,
        // während z. B. die Kamera-App offen ist.
        viewModelScope.launch {
            _edit.map { it?.copy(attachmentBusy = false, saving = false) }.distinctUntilChanged().collect(::persistEdit)
        }
    }

    private fun persistEdit(edit: EditState?) {
        savedState[KEY_EDITING] = edit != null
        if (edit == null) return
        savedState.putParams(KEY_PARAMS, edit.params)
        val replacement = (edit.attachmentChange as? AttachmentChange.Replace)?.attachment
        savedState[KEY_ATTACHMENT_MODE] = when (edit.attachmentChange) {
            AttachmentChange.Keep -> "keep"
            AttachmentChange.Remove -> "remove"
            is AttachmentChange.Replace -> "replace"
        }
        savedState[KEY_ATTACHMENT_DRAFT] = replacement?.file?.absolutePath
        // Nur bei Dateien gesetzt; daran erkennt die Wiederherstellung Foto oder Datei.
        savedState[KEY_ATTACHMENT_NAME] = (replacement as? TextAttachment.Document)?.name
    }

    private fun restoreEdit(): EditState? {
        if (savedState.get<Boolean>(KEY_EDITING) != true) return null
        val draft = savedState.get<String>(KEY_ATTACHMENT_DRAFT)?.let(::File)?.takeIf { it.isFile }
        val name = savedState.get<String>(KEY_ATTACHMENT_NAME)
        val change = when (savedState.get<String>(KEY_ATTACHMENT_MODE)) {
            "remove" -> AttachmentChange.Remove
            "replace" -> draft
                ?.let { if (name != null) TextAttachment.Document(it, name) else TextAttachment.Photo(it) }
                ?.let { AttachmentChange.Replace(it) }
                ?: AttachmentChange.Keep
            else -> AttachmentChange.Keep
        }
        return EditState(params = savedState.getParams(KEY_PARAMS), attachmentChange = change)
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val current: Recording? get() = (load.value as? DetailLoad.Ready)?.recording

    fun audioFile(recording: Recording): File = files.audio(recording.audioFile)

    fun attachment(recording: Recording): TextAttachment? = container.repository.attachmentOf(recording)

    /** Der Text, der im Bearbeiten-Modus gerade angezeigt werden soll. */
    fun editedAttachment(recording: Recording, edit: EditState): TextAttachment? = when (val change = edit.attachmentChange) {
        AttachmentChange.Keep -> attachment(recording)
        AttachmentChange.Remove -> null
        is AttachmentChange.Replace -> change.attachment
    }

    // --- Bearbeiten ---

    fun startEdit() {
        val recording = current ?: return
        _edit.value = EditState(params = recording.params())
    }

    fun cancelEdit() {
        discardAttachmentDraft()
        _edit.value = null
    }

    fun updateParams(params: RecordingParams) = _edit.update { it?.copy(params = params) }

    fun cameraTarget(): Uri = files.prepareCameraCapture()

    fun onCameraPhoto() = importAttachment(isPhoto = true) { TextAttachment.Photo(files.importCameraCapture(app)) }

    fun onPhotoPicked(uri: Uri) = importAttachment(isPhoto = true) { TextAttachment.Photo(files.importDraftPhoto(app, uri)) }

    fun onDocumentPicked(uri: Uri) = importAttachment(isPhoto = false) { files.importDraftDocument(app, uri) }

    private fun importAttachment(isPhoto: Boolean, load: suspend () -> TextAttachment) {
        if (_edit.value == null) return
        viewModelScope.launch {
            _edit.update { it?.copy(attachmentBusy = true) }
            try {
                val attachment = load()
                discardAttachmentDraft()
                if (_edit.value == null) {
                    attachment.file.delete()
                } else {
                    _edit.update { it?.copy(attachmentChange = AttachmentChange.Replace(attachment), attachmentBusy = false) }
                }
            } catch (e: Exception) {
                _edit.update { it?.copy(attachmentBusy = false) }
                _message.value = when {
                    isPhoto -> "Foto konnte nicht übernommen werden: ${e.message}"
                    e is IOException && !e.message.isNullOrBlank() -> e.message
                    else -> "Die Datei konnte nicht übernommen werden."
                }
            }
        }
    }

    fun removeAttachment() {
        discardAttachmentDraft()
        _edit.update { it?.copy(attachmentChange = AttachmentChange.Remove) }
    }

    private fun discardAttachmentDraft() {
        (_edit.value?.attachmentChange as? AttachmentChange.Replace)?.attachment?.file?.delete()
    }

    fun openExternally(document: TextAttachment.Document, onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(files.openWithChooser(document))
            } catch (e: Exception) {
                _message.value = "Die Datei lässt sich nicht öffnen: ${e.message}"
            }
        }
    }

    fun saveEdit() {
        val edit = _edit.value ?: return
        if (edit.saving || edit.attachmentBusy) return
        _edit.value = edit.copy(saving = true)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { container.repository.update(id, edit.params, edit.attachmentChange) }
                _edit.value = null
            } catch (e: Exception) {
                _edit.update { it?.copy(saving = false) }
                _message.value = "Speichern fehlgeschlagen: ${e.message}"
            }
        }
    }

    // --- Teilen und Löschen ---

    /** Kopiert die Aufnahme mit lesbarem Dateinamen und liefert einen Teilen-Dialog. */
    fun share(onReady: (Intent) -> Unit) {
        val recording = current ?: return
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "shared").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(recording.recordedAt))
                    val target = File(dir, "Stimmtraining_$stamp.${audioFile(recording).extension}")
                    audioFile(recording).copyTo(target, overwrite = true)
                    files.uriFor(target)
                }
                val send = Intent(Intent.ACTION_SEND)
                    .setType("audio/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onReady(Intent.createChooser(send, "Aufnahme teilen"))
            } catch (e: Exception) {
                _message.value = "Teilen nicht möglich: ${e.message}"
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val recording = current ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { container.repository.delete(recording) }
            onDeleted()
        }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        private const val KEY_EDITING = "editing"
        private const val KEY_PARAMS = "edit"
        private const val KEY_ATTACHMENT_MODE = "attachmentMode"
        private const val KEY_ATTACHMENT_DRAFT = "attachmentDraft"
        private const val KEY_ATTACHMENT_NAME = "attachmentName"

        fun factory(id: Long) = viewModelFactory {
            initializer {
                val app = app()
                DetailViewModel(id, app, app.container, createSavedStateHandle())
            }
        }
    }
}
