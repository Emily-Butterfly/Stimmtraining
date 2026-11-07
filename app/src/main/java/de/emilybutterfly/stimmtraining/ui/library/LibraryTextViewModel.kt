package de.emilybutterfly.stimmtraining.ui.library

import android.app.Application
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.data.AttachmentChange
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
import de.emilybutterfly.stimmtraining.data.library.LibraryText
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.ui.AttachmentEditor
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.openWithChooser
import de.emilybutterfly.stimmtraining.ui.prepareCameraCapture
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LibraryLoad {
    data object Loading : LibraryLoad
    data object Missing : LibraryLoad
    data class Ready(val text: LibraryText) : LibraryLoad
}

/** Titel und Schlagworte beim Anlegen oder Bearbeiten. [titleEdited]: selbst getippt, nicht mehr automatisch. */
data class LibraryEdit(
    val title: String = "",
    val tags: List<String> = emptyList(),
    val titleEdited: Boolean = false,
    val saving: Boolean = false,
)

/**
 * Ein Text der Bibliothek: ansehen, bearbeiten, Fortschritt ändern, löschen. Ohne [id] wird ein
 * neuer Text angelegt.
 */
class LibraryTextViewModel(
    private val id: Long?,
    private val app: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val repository = container.library
    val isNew: Boolean get() = id == null

    val load: StateFlow<LibraryLoad> = (if (id == null) flowOf(null) else repository.observe(id))
        .map { if (id == null) LibraryLoad.Loading else if (it == null) LibraryLoad.Missing else LibraryLoad.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryLoad.Loading)

    private val current: LibraryText? get() = (load.value as? LibraryLoad.Ready)?.text

    /** Alle schon benutzten Schlagworte, für Vorschläge. */
    val knownTags: StateFlow<List<String>> = repository.observeAll()
        .map { all -> all.flatMap { it.tagList }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val editor = AttachmentEditor(
        app = app,
        files = container.files,
        scope = viewModelScope,
        savedState = savedState,
        stored = { current?.let(repository::attachmentOf) },
        onMessage = { _message.value = it },
    )

    /** Neu angelegt unter dieser ID: Die Ansicht schließt sich dann (als Zustand, siehe RecordUiState.savedId). */
    private val _created = MutableStateFlow(savedState.get<Long>(KEY_CREATED))
    val created: StateFlow<Long?> = _created.asStateFlow()

    private val _edit = MutableStateFlow(restoreEdit())
    val edit: StateFlow<LibraryEdit?> = _edit.asStateFlow()

    init {
        viewModelScope.launch {
            _edit.collect { edit ->
                savedState[KEY_EDITING] = edit != null
                if (edit != null) {
                    savedState[KEY_TITLE] = edit.title
                    savedState[KEY_TAGS] = edit.tags.joinToString("\n")
                    savedState[KEY_TITLE_EDITED] = edit.titleEdited
                }
            }
        }
        // Ein neuer Text bekommt seinen Titel aus dem Inhalt, solange man ihn nicht selbst ändert.
        viewModelScope.launch {
            editor.state.collect { state ->
                val attachment = (state.change as? AttachmentChange.Replace)?.attachment ?: return@collect
                _edit.update { edit ->
                    if (edit == null || edit.titleEdited) edit else edit.copy(title = repository.suggestedTitle(attachment, photoTitle(attachment)))
                }
            }
        }
    }

    private fun restoreEdit(): LibraryEdit? {
        if (id != null && savedState.get<Boolean>(KEY_EDITING) != true) return null
        return LibraryEdit(
            title = savedState[KEY_TITLE] ?: "",
            tags = savedState.get<String>(KEY_TAGS)?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            titleEdited = savedState[KEY_TITLE_EDITED] ?: false,
        )
    }

    fun attachment(text: LibraryText): TextAttachment? = repository.attachmentOf(text)

    /** Der Text, wie er gerade angezeigt wird: im Bearbeiten-Modus mit den noch nicht gespeicherten Änderungen. */
    fun shownAttachment(): TextAttachment? = editor.shown()

    // --- Bearbeiten ---

    fun startEdit() {
        val text = current ?: return
        editor.discard()
        _edit.value = LibraryEdit(title = text.title, tags = text.tagList, titleEdited = true)
    }

    fun cancelEdit() {
        editor.discard()
        _edit.value = null
    }

    /** Gibt es beim Anlegen oder Bearbeiten etwas zu verlieren? */
    fun hasChanges(): Boolean {
        val edit = _edit.value ?: return false
        val text = current
        return editor.changed || if (text == null) edit.title.isNotBlank() || edit.tags.isNotEmpty() else edit.title != text.title || edit.tags != text.tagList
    }

    fun updateTitle(title: String) = _edit.update { it?.copy(title = title.take(120), titleEdited = true) }

    fun addTag(tag: String) {
        val clean = tag.replace(Regex("\\s+"), " ").trim().take(LibraryText.MAX_TAG_LENGTH)
        if (clean.isEmpty()) return
        _edit.update { edit -> edit?.copy(tags = if (edit.tags.any { it.equals(clean, ignoreCase = true) }) edit.tags else edit.tags + clean) }
    }

    fun removeTag(tag: String) = _edit.update { edit -> edit?.copy(tags = edit.tags - tag) }

    fun cameraTarget() = container.files.prepareCameraCapture()

    fun save() {
        val edit = _edit.value ?: return
        if (edit.saving || editor.state.value.busy) return
        val state = editor.state.value
        _edit.value = edit.copy(saving = true)
        viewModelScope.launch {
            try {
                if (id == null) {
                    val attachment = (state.change as? AttachmentChange.Replace)?.attachment
                    if (attachment == null) {
                        _edit.update { it?.copy(saving = false) }
                        _message.value = "Füge zuerst den Text hinzu: Foto, Datei oder eingefügt."
                        return@launch
                    }
                    val shortened = repository.isShortened(attachment)
                    val newId = withContext(NonCancellable) {
                        repository.add(attachment, edit.title.ifBlank { repository.suggestedTitle(attachment, photoTitle(attachment)) }, edit.tags, move = true)
                    }
                    if (shortened) _message.value = LibraryRepository.SHORTENED
                    editor.committed(state)
                    _edit.value = null
                    savedState[KEY_CREATED] = newId
                    _created.value = newId
                } else {
                    if (state.change == AttachmentChange.Remove) {
                        _edit.update { it?.copy(saving = false) }
                        _message.value = "Ein Text in der Bibliothek braucht einen Inhalt. Lösche ihn stattdessen, wenn du ihn nicht mehr brauchst."
                        return@launch
                    }
                    val lost = withContext(NonCancellable) {
                        repository.update(id, edit.title, edit.tags, state.change, state.origins)
                    }
                    editor.committed(state)
                    _edit.value = null
                    if (lost) _message.value = "Die Stelle, bis zu der du gelesen hattest, gibt es nicht mehr – der Text beginnt wieder von vorne."
                }
            } catch (e: Exception) {
                _edit.update { it?.copy(saving = false) }
                _message.value = "Speichern fehlgeschlagen: ${e.message}"
            }
        }
    }

    // --- Ansehen ---

    fun togglePinned() {
        val text = current ?: return
        safely("Das Anheften ist fehlgeschlagen") { repository.setPinned(text.id, !text.pinned) }
    }

    /**
     * Führt eine Datenbankänderung im Hintergrund aus. Ein Fehler (z. B. Speicher voll) wird als
     * Meldung gezeigt, statt die App zu beenden.
     */
    private fun safely(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { block() }
            } catch (e: Exception) {
                _message.value = "$failure: ${e.message}"
            }
        }
    }

    /** Setzt die gelesene Stelle von Hand; null beginnt den Text von vorne. */
    fun setProgress(position: ReadingPosition?) {
        val text = current ?: return
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { repository.setProgress(text.id, position) }
            } catch (e: Exception) {
                _message.value = "Die Stelle ließ sich nicht speichern: ${e.message}"
            }
        }
    }

    fun readAgain() {
        val text = current ?: return
        safely("„Nochmal lesen“ ist fehlgeschlagen") { repository.readAgain(text.id) }
    }

    fun openExternally(document: TextAttachment.Document, onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(container.files.openWithChooser(document))
            } catch (e: Exception) {
                _message.value = "Die Datei lässt sich nicht öffnen: ${e.message}"
            }
        }
    }

    /** Danach meldet [load] „gibt es nicht mehr“, und die Ansicht schließt sich. */
    fun delete() {
        val text = current ?: return
        safely("Das Löschen ist fehlgeschlagen") { repository.delete(text.id) }
    }

    fun messageShown() {
        _message.value = null
    }

    private fun photoTitle(attachment: TextAttachment) = LibraryRepository.photoTitle(attachment)

    companion object {
        private const val KEY_EDITING = "libraryEditing"
        private const val KEY_CREATED = "libraryCreated"
        private const val KEY_TITLE = "libraryTitle"
        private const val KEY_TAGS = "libraryTags"
        private const val KEY_TITLE_EDITED = "libraryTitleEdited"

        fun factory(id: Long?) = viewModelFactory {
            initializer {
                val app = app()
                LibraryTextViewModel(id, app, app.container, createSavedStateHandle())
            }
        }
    }
}
