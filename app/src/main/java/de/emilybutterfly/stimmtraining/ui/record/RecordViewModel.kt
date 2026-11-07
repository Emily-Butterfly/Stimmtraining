package de.emilybutterfly.stimmtraining.ui.record

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.analysis.PitchAnalyzer
import de.emilybutterfly.stimmtraining.analysis.PitchResult
import de.emilybutterfly.stimmtraining.audio.AudioRecorder
import de.emilybutterfly.stimmtraining.data.Exercise
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.data.pageNames
import de.emilybutterfly.stimmtraining.ui.DraftPages
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.createDraftText
import de.emilybutterfly.stimmtraining.ui.getParams
import de.emilybutterfly.stimmtraining.ui.importCameraCapture
import de.emilybutterfly.stimmtraining.ui.importDraftDocument
import de.emilybutterfly.stimmtraining.ui.importDraftPhotos
import de.emilybutterfly.stimmtraining.ui.importFailureMessage
import de.emilybutterfly.stimmtraining.ui.openWithChooser
import de.emilybutterfly.stimmtraining.ui.pageFailureMessage
import de.emilybutterfly.stimmtraining.ui.skippedPhotosMessage
import de.emilybutterfly.stimmtraining.ui.prepareCameraCapture
import de.emilybutterfly.stimmtraining.ui.putParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

enum class RecorderPhase { Idle, Recording, Paused, Done }

/**
 * Hinweis vor dem Aufnehmen, dass der Speicher knapp ist. Bei [full] reicht er nicht einmal
 * für eine kurze Aufnahme.
 */
data class StorageWarning(val freeBytes: Long, val full: Boolean) {
    /** Etwa so viele Minuten lassen sich noch aufnehmen. */
    val minutesLeft: Long
        get() = ((freeBytes - FileStore.RESERVE_BYTES) / FileStore.AUDIO_BYTES_PER_MINUTE).coerceAtLeast(0)
}

/**
 * Ein Text aus der Bibliothek, der gerade gelesen wird. Seine Dateien gehören der Bibliothek und
 * werden hier nie gelöscht; gespeichert wird nur der gelesene Abschnitt.
 */
data class LibraryReading(
    val textId: Long,
    val title: String,
    val attachment: TextAttachment,
    /** Ab hier wird gelesen; null: von vorne. */
    val start: ReadingPosition? = null,
    /** Die angetippte Stelle; null: bis zum Ende. */
    val end: ReadingPosition? = null,
    /** Der Text war schon zu Ende gelesen und wird noch einmal von vorne gelesen. */
    val again: Boolean = false,
)

data class RecordUiState(
    val params: RecordingParams = RecordingParams(),
    /** Der gelesene Text: Foto oder Datei (eigene Entwürfe). */
    val attachment: TextAttachment? = null,
    /** Stattdessen ein Text aus der Bibliothek. */
    val library: LibraryReading? = null,
    val attachmentBusy: Boolean = false,
    val phase: RecorderPhase = RecorderPhase.Idle,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val audio: File? = null,
    /** Datum und Uhrzeit des Aufnahmebeginns. */
    val recordedAt: Long? = null,
    val durationMs: Long = 0,
    val saving: Boolean = false,
    /**
     * Gespeichert unter dieser ID: Die Ansicht wechselt dann zur Aufnahme. Als Zustand statt als
     * Rückruf, damit es auch nach dem Drehen oder einem Neustart durch Android ankommt.
     */
    val savedId: Long? = null,
    /** Eine andere App hat gerade das Mikrofon; aufgenommen wird Stille. */
    val micSilenced: Boolean = false,
    val message: String? = null,
    /** Ausgangswerte des Formulars, z. B. mit vorausgefüllter Übung. */
    val initialParams: RecordingParams = RecordingParams(),
    val storageWarning: StorageWarning? = null,
    /** Tonhöhe der fertigen Aufnahme, falls schon vor dem Speichern analysiert. */
    val pitch: PitchResult? = null,
    /** Fortschritt (0..1) der laufenden Analyse, sonst null. */
    val pitchProgress: Float? = null,
) {
    val isRecording: Boolean get() = phase == RecorderPhase.Recording || phase == RecorderPhase.Paused

    /** Auch ein noch laufender Import zählt, damit „Zurück“ erst nachfragt. */
    val hasDraft: Boolean
        get() = attachment != null || library != null || attachmentBusy || phase != RecorderPhase.Idle || params != initialParams

    /** Der Text, der angezeigt wird: eigener Anhang oder der aus der Bibliothek. */
    val shownText: TextAttachment? get() = attachment ?: library?.attachment
}

/** Die Übung aus „Techniken & Übungen“, mit der diese Aufnahme gestartet wurde. */
data class ExerciseGuide(val label: String, val exercise: Exercise)

class RecordViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
    private val freeBytes: () -> Long = { container.files.freeBytes() },
) : ViewModel() {
    private val recorder = AudioRecorder(app).also { recorder ->
        recorder.onError = { viewModelScope.launch { onRecorderError() } }
    }

    /** Hat Android die laufende Aufnahme abgebrochen? Dann ehrlich melden statt „zu kurz“. */
    private var recorderFailed = false
    private val hadSavedParams = savedState.contains("$KEY_PARAMS.title")
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private val _guide = MutableStateFlow<ExerciseGuide?>(null)
    val guide: StateFlow<ExerciseGuide?> = _guide.asStateFlow()

    /**
     * Erst wenn feststeht, ob es eine Anleitung gibt, wird der Inhalt gezeigt. Sonst würde
     * die nachträglich eingefügte Anleitung eine wiederhergestellte Scrollposition verschieben.
     */
    private val _guideReady = MutableStateFlow(savedState.get<String>(ARG_TECHNIQUE) == null)
    val guideReady: StateFlow<Boolean> = _guideReady.asStateFlow()

    private var recordingFile: File? = null
    private var accumulatedMs = 0L
    private var segmentStartedAt = 0L
    private var ticker: Job? = null
    private var pitchJob: Job? = null
    private var importJob: Job? = null
    private var libraryJob: Job? = null

    /**
     * Solange ein gesicherter Bibliothekstext noch geladen wird, bleibt sein gesicherter Stand
     * stehen. Muss vor dem init-Block stehen: Das Sichern beginnt dort sofort.
     */
    private var restoringLibrary = savedState.get<Long>(KEY_LIBRARY_ID) != null

    init {
        // Entwurf sichern, damit er überlebt, wenn Android die App im Hintergrund beendet
        // (z. B. während die Kamera-App offen ist).
        viewModelScope.launch {
            _state.map(::persistable).distinctUntilChanged().collect(::persist)
        }
        loadGuide()
        restoreLibrary()
        // Wer die Tonhöhe ausblendet, soll auch keine laufende Analyse mehr spüren (Meldungen, Warten beim Speichern).
        viewModelScope.launch {
            container.settings.gentleMode.collect { gentle -> if (gentle) pitchJob?.cancel() }
        }
    }

    /** Lädt die Übung, mit der die Aufnahme gestartet wurde, und trägt sie ins Formular ein. */
    private fun loadGuide() {
        val techniqueId = savedState.get<String>(ARG_TECHNIQUE) ?: return
        val exerciseId = savedState.get<String>(ARG_EXERCISE)
        viewModelScope.launch {
            var first = true
            // Die Anleitung folgt dem sanften Modus, auch wenn er während der Aufnahme umgeschaltet wird.
            container.settings.gentleMode.collectLatest { gentle ->
                val technique = withContext(Dispatchers.IO) { runCatching { container.techniques(gentle) }.getOrNull() }
                    ?.technique(techniqueId)
                val exercise = exerciseId?.let { technique?.exercise(it) }
                if (technique == null || exercise == null) {
                    _guideReady.value = true
                    return@collectLatest
                }
                val guide = ExerciseGuide(technique.recordingLabel(exercise), exercise)
                _guide.value = guide
                _guideReady.value = true
                // Titel und Bezeichnung sind in beiden Fassungen gleich; eingetragen wird nur einmal.
                if (first && !hadSavedParams) {
                    _state.update {
                        if (it.params.exercise.isNotBlank()) {
                            it
                        } else {
                            val params = it.params.copy(exercise = guide.label)
                            it.copy(params = params, initialParams = params)
                        }
                    }
                }
                first = false
            }
        }
    }

    private data class Persistable(
        val params: RecordingParams,
        val initialParams: RecordingParams,
        val libraryId: Long?,
        val libraryEnd: ReadingPosition?,
        val photo: String?,
        val document: String?,
        val documentName: String?,
        val audio: String?,
        val recordedAt: Long?,
        val durationMs: Long,
        val pitch: PitchResult?,
    )

    private fun persistable(state: RecordUiState): Persistable {
        val done = state.phase == RecorderPhase.Done
        return Persistable(
            params = state.params,
            initialParams = state.initialParams,
            libraryId = state.library?.textId,
            libraryEnd = state.library?.end,
            photo = (state.attachment as? TextAttachment.Photo)?.pages?.joinToString("\n") { it.absolutePath },
            document = (state.attachment as? TextAttachment.Document)?.file?.absolutePath,
            documentName = (state.attachment as? TextAttachment.Document)?.name,
            audio = if (done) state.audio?.absolutePath else null,
            recordedAt = if (done) state.recordedAt else null,
            durationMs = if (done) state.durationMs else 0,
            pitch = if (done) state.pitch else null,
        )
    }

    private fun persist(p: Persistable) {
        savedState.putParams(KEY_PARAMS, p.params)
        savedState.putParams(KEY_INITIAL_PARAMS, p.initialParams)
        // Erst setzen, wenn der Text geladen ist: Bis dahin gilt noch der gesicherte Stand.
        if (!restoringLibrary) {
            savedState[KEY_LIBRARY_ID] = p.libraryId
            val end = p.libraryEnd
            savedState[KEY_LIBRARY_END_OFFSET] = (end as? ReadingPosition.InText)?.offset
            savedState[KEY_LIBRARY_END_ANCHOR] = (end as? ReadingPosition.InText)?.anchor
            savedState[KEY_LIBRARY_END_PAGE] = (end as? ReadingPosition.OnPage)?.page
            savedState[KEY_LIBRARY_END_Y] = (end as? ReadingPosition.OnPage)?.y
            savedState[KEY_LIBRARY_END_PAGE_NAME] = (end as? ReadingPosition.OnPage)?.pageName
        }
        savedState[KEY_PHOTO] = p.photo
        savedState[KEY_DOCUMENT] = p.document
        savedState[KEY_DOCUMENT_NAME] = p.documentName
        savedState[KEY_AUDIO] = p.audio
        savedState[KEY_RECORDED_AT] = p.recordedAt
        savedState[KEY_DURATION] = p.durationMs
        savedState[KEY_PITCH_VOICED] = p.pitch?.voicedMs
        savedState[KEY_PITCH_MOST] = p.pitch?.mostFrequent
        savedState[KEY_PITCH_LOW] = p.pitch?.lowest
        savedState[KEY_PITCH_HIGH] = p.pitch?.highest
    }

    private fun restore(): RecordUiState {
        // Schon gespeichert, nur der Wechsel zur Aufnahme fehlte noch (z. B. App währenddessen beendet).
        savedState.get<Long>(KEY_SAVED_ID)?.let { return RecordUiState(savedId = it, saving = true) }
        val params = savedState.getParams(KEY_PARAMS)
        val initialParams = savedState.getParams(KEY_INITIAL_PARAMS)
        val attachment = restoreAttachment()
        val audio = container.files.ownDraft(savedState.get<String>(KEY_AUDIO))?.takeIf { it.length() > 0 }
            ?: return RecordUiState(params = params, attachment = attachment, initialParams = initialParams)
        val duration = savedState[KEY_DURATION] ?: 0L
        return RecordUiState(
            params = params,
            initialParams = initialParams,
            attachment = attachment,
            phase = RecorderPhase.Done,
            audio = audio,
            recordedAt = savedState[KEY_RECORDED_AT],
            durationMs = duration,
            elapsedMs = duration,
            pitch = savedState.get<Long>(KEY_PITCH_VOICED)?.let { voicedMs ->
                PitchResult(savedState[KEY_PITCH_MOST], savedState[KEY_PITCH_LOW], savedState[KEY_PITCH_HIGH], voicedMs)
            },
        )
    }

    private fun restoreAttachment(): TextAttachment? {
        container.files.ownDraft(savedState.get<String>(KEY_DOCUMENT))?.let { file ->
            return TextAttachment.Document(file, savedState.get<String>(KEY_DOCUMENT_NAME) ?: file.name)
        }
        val pages = savedState.get<String>(KEY_PHOTO).pageNames().mapNotNull(container.files::ownDraft)
        return if (pages.isEmpty()) null else TextAttachment.Photo(pages)
    }

    fun updateParams(params: RecordingParams) = _state.update { it.copy(params = params) }

    // --- Text aus der Bibliothek ---

    /** Nach einem Neustart durch Android: den gewählten Text samt angetippter Stelle wieder laden. */
    private fun restoreLibrary() {
        val saved = savedState.get<Long>(KEY_LIBRARY_ID)
        val fromArgument = savedState.get<Long>(ARG_LIBRARY_TEXT)?.takeIf { it > 0 && savedState.get<Boolean>(KEY_LIBRARY_ARG_USED) != true }
        when {
            saved != null -> {
                val end = savedState.get<Int>(KEY_LIBRARY_END_OFFSET)?.let { offset ->
                    savedState.get<String>(KEY_LIBRARY_END_ANCHOR)?.let { ReadingPosition.InText(offset, it) }
                } ?: savedState.get<Int>(KEY_LIBRARY_END_PAGE)?.let { page ->
                    savedState.get<Float>(KEY_LIBRARY_END_Y)?.let { ReadingPosition.OnPage(page, it, savedState[KEY_LIBRARY_END_PAGE_NAME]) }
                }
                loadLibraryText(saved, end) { restoringLibrary = false }
            }
            fromArgument != null -> {
                savedState[KEY_LIBRARY_ARG_USED] = true
                selectLibraryText(fromArgument)
            }
        }
    }

    /** Wählt einen Text der Bibliothek; ein eigener Anhang wird dabei verworfen. */
    fun selectLibraryText(id: Long) {
        if (_state.value.isRecording || _state.value.saving) return
        loadLibraryText(id, end = null) {}
    }

    private fun loadLibraryText(id: Long, end: ReadingPosition?, done: () -> Unit) {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _state.update { it.copy(attachmentBusy = true) }
            try {
                val text = container.library.get(id)
                val attachment = text?.let(container.library::attachmentOf)
                if (text == null || attachment == null) {
                    _state.update { it.copy(attachmentBusy = false, library = null, message = "Diesen Text gibt es nicht mehr in der Bibliothek.") }
                    return@launch
                }
                val start = container.library.startOf(text)
                val previous = _state.value.attachment
                _state.update {
                    it.copy(
                        attachment = null,
                        attachmentBusy = false,
                        library = LibraryReading(text.id, text.title, attachment, start.position, end, again = text.isFinished),
                        message = if (start.lost) POSITION_LOST else it.message,
                    )
                }
                previous?.files?.forEach { it.delete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(attachmentBusy = false, message = "Der Text ließ sich nicht laden: ${e.message}") }
            } finally {
                done()
                persist(persistable(_state.value))
            }
        }
    }

    /** Bis hier gelesen; null heißt bis zum Ende. */
    fun markLibraryEnd(position: ReadingPosition?) = _state.update { it.copy(library = it.library?.copy(end = position)) }

    /** Den Bibliothekstext nicht mehr verwenden; er bleibt unverändert in der Bibliothek. */
    fun removeLibraryText() {
        libraryJob?.cancel()
        _state.update { it.copy(library = null, attachmentBusy = false) }
    }

    /** Legt den eigenen Anhang in die Bibliothek und liest ihn von dort – mit Fortschritt. */
    fun addAttachmentToLibrary() {
        val attachment = _state.value.attachment ?: return
        if (_state.value.attachmentBusy || _state.value.saving) return
        importJob = viewModelScope.launch {
            _state.update { it.copy(attachmentBusy = true) }
            try {
                val title = container.library.suggestedTitle(attachment, LibraryRepository.photoTitle(attachment))
                val shortened = container.library.isShortened(attachment)
                val id = withContext(NonCancellable) { container.library.add(attachment, title, move = true) }
                _state.update { it.copy(attachment = null) }
                loadLibraryText(id, end = null) {}
                _state.update {
                    it.copy(
                        message = if (shortened) {
                            LibraryRepository.SHORTENED
                        } else {
                            "In die Bibliothek gelegt. Tippe nach dem Lesen auf die Stelle, bis zu der du gekommen bist."
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(attachmentBusy = false, message = "Das Übernehmen in die Bibliothek ist fehlgeschlagen: ${e.message}") }
            }
        }
    }

    // --- Text: Foto oder Datei ---

    fun cameraTarget(): Uri = container.files.prepareCameraCapture()

    /** Ein Kamerafoto ersetzt den Text oder kommt mit [addPage] als weitere Seite dazu. */
    fun onCameraPhoto(addPage: Boolean = false) {
        if (addPage) {
            addPages { listOf(container.files.importCameraCapture(app)) }
        } else {
            importAttachment(isPhoto = true) { TextAttachment.Photo(container.files.importCameraCapture(app)) }
        }
    }

    /** Bilder aus der Galerie, in der gewählten Reihenfolge. */
    fun onPhotosPicked(uris: List<Uri>, addPage: Boolean = false) {
        if (uris.isEmpty()) return
        // Mehr als in einen Anhang passt, wird gar nicht erst übernommen.
        val limited = uris.take(TextAttachment.MAX_PAGES)
        if (addPage) {
            addPages { container.files.importDraftPhotos(app, limited, ::reportSkipped) }
        } else {
            importAttachment(isPhoto = true) { DraftPages.append(null, container.files.importDraftPhotos(app, limited, ::reportSkipped)).first }
        }
        if (uris.size > limited.size) _state.update { it.copy(message = TOO_MANY_PAGES) }
    }

    private fun reportSkipped(count: Int) = _state.update { it.copy(message = skippedPhotosMessage(count)) }

    private fun addPages(load: suspend () -> List<File>) {
        importJob = viewModelScope.launch {
            _state.update { it.copy(attachmentBusy = true) }
            try {
                val added = load()
                val current = _state.value.attachment
                val (photo, dropped) = if (current is TextAttachment.Photo) {
                    DraftPages.append(current, added)
                } else {
                    current?.files?.forEach { it.delete() }
                    DraftPages.append(null, added)
                }
                _state.update {
                    it.copy(attachment = photo, library = null, attachmentBusy = false, message = if (dropped > 0) TOO_MANY_PAGES else it.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(attachmentBusy = false, message = importFailureMessage(isPhoto = true, e)) }
            }
        }
    }

    fun rotatePage(index: Int, clockwise: Boolean) {
        val photo = _state.value.attachment as? TextAttachment.Photo ?: return
        if (_state.value.attachmentBusy) return
        importJob = viewModelScope.launch {
            _state.update { it.copy(attachmentBusy = true) }
            try {
                val rotated = DraftPages.rotate(container.files, photo, index, clockwise)
                _state.update { it.copy(attachment = rotated, attachmentBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(attachmentBusy = false, message = pageFailureMessage(e)) }
            }
        }
    }

    fun movePage(from: Int, to: Int) {
        val photo = _state.value.attachment as? TextAttachment.Photo ?: return
        if (!_state.value.attachmentBusy) _state.update { it.copy(attachment = DraftPages.move(photo, from, to)) }
    }

    fun deletePage(index: Int) {
        val photo = _state.value.attachment as? TextAttachment.Photo ?: return
        if (!_state.value.attachmentBusy) _state.update { it.copy(attachment = DraftPages.delete(photo, index)) }
    }

    fun onDocumentPicked(uri: Uri) = importAttachment(isPhoto = false) { container.files.importDraftDocument(app, uri) }

    fun onTextPasted(text: String, name: String?) {
        if (text.isBlank()) return
        importAttachment(isPhoto = false) { container.files.createDraftText(text, name) }
    }

    private fun importAttachment(isPhoto: Boolean, load: suspend () -> TextAttachment) {
        importJob = viewModelScope.launch {
            _state.update { it.copy(attachmentBusy = true) }
            try {
                val attachment = load()
                val previous = _state.value.attachment
                _state.update { it.copy(attachment = attachment, library = null, attachmentBusy = false) }
                previous?.files?.forEach { it.delete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(attachmentBusy = false, message = importFailureMessage(isPhoto, e)) }
            }
        }
    }

    fun removeAttachment() {
        _state.value.attachment?.files?.forEach { it.delete() }
        _state.update { it.copy(attachment = null) }
    }

    /** Bereitet das Öffnen der Datei in einer anderen App vor (nicht während einer Aufnahme). */
    fun openExternally(document: TextAttachment.Document, onReady: (Intent) -> Unit) {
        if (_state.value.isRecording) return
        viewModelScope.launch {
            try {
                val chooser = container.files.openWithChooser(document)
                // Inzwischen gestartet? Dann würde die andere App die Aufnahme beenden.
                if (!_state.value.isRecording) onReady(chooser)
            } catch (e: Exception) {
                _state.update { it.copy(message = "Die Datei lässt sich nicht öffnen: ${e.message}") }
            }
        }
    }

    // --- Aufnahme ---

    /**
     * Startet die Aufnahme. Ist der Speicher knapp, erscheint zuerst ein Hinweis
     * ([RecordUiState.storageWarning]); mit [ignoreLowStorage] wird trotzdem aufgenommen.
     */
    fun startRecording(ignoreLowStorage: Boolean = false) {
        if (_state.value.phase != RecorderPhase.Idle || recorder.isActive) return
        val free = freeBytes()
        val budget = free - FileStore.RESERVE_BYTES
        if (budget < MIN_RECORDING_BYTES) {
            _state.update { it.copy(storageWarning = StorageWarning(free, full = true)) }
            return
        }
        if (!ignoreLowStorage && free < FileStore.LOW_STORAGE_BYTES) {
            _state.update { it.copy(storageWarning = StorageWarning(free, full = false)) }
            return
        }
        _state.update { it.copy(storageWarning = null) }
        val file = container.files.newDraftFile("m4a")
        try {
            recorder.start(file)
        } catch (e: Exception) {
            file.delete()
            _state.update { it.copy(message = "Aufnahme konnte nicht gestartet werden: ${e.message}") }
            return
        }
        recordingFile = file
        accumulatedMs = 0
        segmentStartedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                phase = RecorderPhase.Recording,
                elapsedMs = 0,
                level = 0f,
                audio = null,
                recordedAt = System.currentTimeMillis(),
                durationMs = 0,
            )
        }
        startTicker()
    }

    fun dismissStorageWarning() = _state.update { it.copy(storageWarning = null) }

    private fun onRecorderError() {
        if (!_state.value.isRecording) return
        recorderFailed = true
        stopRecording()
        if (_state.value.phase == RecorderPhase.Done) {
            _state.update { it.copy(message = "Android hat die Aufnahme abgebrochen. Was bis dahin aufgenommen war, ist erhalten.") }
        }
    }

    /** Bevor der Speicher vollläuft, endet die Aufnahme sauber und bleibt abspielbar. */
    private fun onStorageLimitReached() {
        if (!_state.value.isRecording) return
        stopRecording()
        if (_state.value.phase == RecorderPhase.Done) {
            _state.update {
                it.copy(message = "Der Speicher ist fast voll – die Aufnahme wurde beendet. Du kannst sie anhören und speichern.")
            }
        }
    }

    fun pauseOrResume() {
        when (_state.value.phase) {
            RecorderPhase.Recording -> {
                try {
                    recorder.pause()
                } catch (_: RuntimeException) {
                    // Falscher Zustand oder ein Fehler des Mediendienstes: nichts ändern, nicht abstürzen.
                    return
                }
                accumulatedMs += SystemClock.elapsedRealtime() - segmentStartedAt
                ticker?.cancel()
                _state.update { it.copy(phase = RecorderPhase.Paused, elapsedMs = accumulatedMs, level = 0f) }
            }
            RecorderPhase.Paused -> {
                try {
                    recorder.resume()
                } catch (_: RuntimeException) {
                    // Falscher Zustand oder ein Fehler des Mediendienstes: nichts ändern, nicht abstürzen.
                    return
                }
                segmentStartedAt = SystemClock.elapsedRealtime()
                _state.update { it.copy(phase = RecorderPhase.Recording) }
                startTicker()
            }
            else -> Unit
        }
    }

    fun stopRecording() {
        val phase = _state.value.phase
        if (phase != RecorderPhase.Recording && phase != RecorderPhase.Paused) return
        if (phase == RecorderPhase.Recording) accumulatedMs += SystemClock.elapsedRealtime() - segmentStartedAt
        ticker?.cancel()
        val file = recordingFile
        recordingFile = null
        val ok = recorder.stop()
        // Hat Android die Aufnahme schon selbst beendet (Speichergrenze), kann stop() scheitern,
        // obwohl die Datei vollständig ist.
        val usable = file != null && file.isFile && file.length() > 0 && (ok || AudioRecorder.durationOf(file) != null)
        val failed = recorderFailed
        recorderFailed = false
        if (!usable) {
            file?.delete()
            _state.update {
                it.copy(
                    phase = RecorderPhase.Idle,
                    elapsedMs = 0,
                    level = 0f,
                    recordedAt = null,
                    // Eine Markierung dieses Versuchs gilt nicht für den nächsten: „Bis zum Ende“ ist wieder vorausgewählt.
                    library = it.library?.copy(end = null),
                    message = if (failed) {
                        "Android hat die Aufnahme abgebrochen; es ließ sich nichts davon retten. Bitte nimm noch einmal auf."
                    } else {
                        "Die Aufnahme war zu kurz und wurde verworfen."
                    },
                )
            }
            return
        }
        _state.update {
            it.copy(
                phase = RecorderPhase.Done,
                micSilenced = false,
                level = 0f,
                audio = file,
                elapsedMs = accumulatedMs,
                durationMs = accumulatedMs,
            )
        }
        viewModelScope.launch {
            val exact = withContext(Dispatchers.IO) { AudioRecorder.durationOf(file) }
            if (exact != null) _state.update { if (it.audio == file) it.copy(durationMs = exact) else it }
        }
    }

    /**
     * Im Hintergrund liefert Android kein Mikrofonsignal mehr, und die App kann jederzeit
     * beendet werden. Eine nur pausierte MP4-Datei wäre dann unbrauchbar, deshalb wird die
     * Aufnahme abgeschlossen; sie lässt sich danach anhören und speichern.
     */
    fun onAppBackgrounded() {
        if (_state.value.isRecording) {
            stopRecording()
            if (_state.value.phase == RecorderPhase.Done) {
                _state.update { it.copy(message = "Aufnahme beendet, weil die App in den Hintergrund gewechselt ist.") }
            }
        }
    }

    // --- Tonhöhe ---

    /** Analysiert die fertige Aufnahme schon vor dem Speichern – nur auf dem Gerät. */
    fun analyzePitch() {
        val current = _state.value
        val audio = current.audio ?: return
        if (current.phase != RecorderPhase.Done || current.saving || pitchJob?.isActive == true) return
        _state.update { it.copy(pitchProgress = 0f) }
        pitchJob = viewModelScope.launch {
            try {
                val result = PitchAnalyzer.analyzeFile(audio) { progress ->
                    _state.update { if (it.audio == audio && it.pitchProgress != null) it.copy(pitchProgress = progress) else it }
                }
                _state.update { if (it.audio == audio) it.copy(pitch = result) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!container.settings.gentleMode.value) {
                    _state.update { it.copy(message = "Die Tonhöhe ließ sich nicht analysieren: ${e.message}") }
                }
            } finally {
                _state.update { it.copy(pitchProgress = null) }
            }
        }
    }

    fun discardRecording() {
        pitchJob?.cancel()
        ticker?.cancel()
        if (recorder.isActive) recorder.stop()
        recordingFile?.delete()
        recordingFile = null
        _state.value.audio?.delete()
        _state.update {
            it.copy(
                phase = RecorderPhase.Idle,
                audio = null,
                elapsedMs = 0,
                durationMs = 0,
                recordedAt = null,
                level = 0f,
                pitch = null,
                pitchProgress = null,
                // Eine neue Aufnahme fragt wieder von vorne: „Bis zum Ende“ ist vorausgewählt.
                library = it.library?.copy(end = null),
            )
        }
    }

    /** Verwirft den ganzen Entwurf: Aufnahme und Text. */
    fun discardDraft() {
        // Ein noch laufender Import räumt seinen Entwurf beim Abbrechen selbst weg.
        importJob?.cancel()
        libraryJob?.cancel()
        _state.update { it.copy(attachmentBusy = false, library = null) }
        discardRecording()
        removeAttachment()
    }

    fun save() {
        val current = _state.value
        val audio = current.audio ?: return
        if (current.saving || current.attachmentBusy) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                // Eine laufende Analyse dauert nur Sekunden; ihr Ergebnis wird mitgespeichert.
                pitchJob?.join()
                // Danach neu lesen: Während des Wartens kann sich das Formular noch geändert haben.
                val now = _state.value
                if (now.audio != audio || now.attachmentBusy) {
                    _state.update { it.copy(saving = false) }
                    return@launch
                }
                val reading = now.library
                val id = withContext(NonCancellable) {
                    // Aus der Bibliothek kommt nur der gelesene Abschnitt an die Aufnahme. Geht das nicht,
                    // wird nicht gespeichert: Sonst wären Text und Fortschritt stillschweigend weg.
                    val section = reading?.let { r ->
                        val text = container.library.get(r.textId) ?: throw IOException(SECTION_GONE)
                        try {
                            container.library.section(text, r.start, r.end)
                        } catch (e: Exception) {
                            throw IOException(SECTION_FAILED, e)
                        } catch (e: OutOfMemoryError) {
                            throw IOException(SECTION_FAILED, e)
                        }
                    }
                    val draft = if (reading != null) section?.attachment else now.attachment
                    val id = try {
                        container.repository.create(
                            draftAudio = audio,
                            draftAttachment = draft,
                            recordedAt = now.recordedAt ?: System.currentTimeMillis(),
                            durationMs = now.durationMs,
                            params = now.params,
                            pitch = now.pitch,
                        )
                    } catch (e: Exception) {
                        if (reading != null) section?.attachment?.files?.forEach { it.delete() }
                        throw e
                    }
                    if (reading != null && section != null) {
                        // Die Aufnahme ist gespeichert; ein Fehler beim Fortschritt soll sie nicht kosten.
                        runCatching {
                            container.library.recordReading(reading.textId, if (section.finished) null else reading.end, section.fraction)
                        }
                    }
                    id
                }
                // Sofort sichern: Endet die App jetzt, darf die Aufnahme nicht ein zweites Mal gespeichert werden.
                savedState[KEY_SAVED_ID] = id
                _state.update { it.copy(savedId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = "Speichern fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var lastStorageCheck = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = accumulatedMs + (now - segmentStartedAt)
                val level = recorder.level()
                _state.update { it.copy(elapsedMs = elapsed, level = max(level, it.level * 0.8f)) }
                // Auch andere Apps können den Speicher während der Aufnahme füllen.
                if (now - lastStorageCheck >= STORAGE_CHECK_MS) {
                    lastStorageCheck = now
                    if (freeBytes() < STOP_RECORDING_BYTES) {
                        onStorageLimitReached()
                        return@launch
                    }
                    val silenced = recorder.isSilenced()
                    if (silenced != _state.value.micSilenced) _state.update { it.copy(micSilenced = silenced) }
                }
                delay(80)
            }
        }
    }

    /**
     * Entwurfsdateien werden hier bewusst nicht gelöscht: Android kann die Activity zerstören
     * und später samt gesichertem Zustand wiederherstellen. Gelöscht wird beim Verwerfen
     * ([discardDraft]); liegen gebliebene Entwürfe räumt FileStore.cleanOldDrafts auf.
     */
    override fun onCleared() {
        ticker?.cancel()
        if (recorder.isActive) recorder.stop()
    }

    companion object {
        internal const val KEY_PARAMS = "params"
        internal const val KEY_INITIAL_PARAMS = "initialParams"

        /** Navigationsargumente, wenn die Aufnahme aus einer Übung heraus gestartet wird. */
        const val ARG_TECHNIQUE = "technique"
        const val ARG_EXERCISE = "exercise"
        /** Mit diesem Bibliothekstext beginnen (z. B. „Für eine Aufnahme verwenden“). */
        const val ARG_LIBRARY_TEXT = "libraryText"
        /** Hier legt die Bibliotheks-Auswahl den gewählten Text ab (im Navigationseintrag der Aufnahme). */
        const val KEY_PICKED_LIBRARY_TEXT = "pickedLibraryText"
        internal const val KEY_LIBRARY_ID = "libraryId"
        internal const val KEY_LIBRARY_ARG_USED = "libraryArgUsed"
        internal const val KEY_LIBRARY_END_OFFSET = "libraryEndOffset"
        internal const val KEY_LIBRARY_END_ANCHOR = "libraryEndAnchor"
        internal const val KEY_LIBRARY_END_PAGE = "libraryEndPage"
        internal const val KEY_LIBRARY_END_Y = "libraryEndY"
        internal const val KEY_LIBRARY_END_PAGE_NAME = "libraryEndPageName"
        internal const val KEY_PHOTO = "photo"
        internal const val KEY_DOCUMENT = "document"
        internal const val KEY_DOCUMENT_NAME = "documentName"
        internal const val KEY_AUDIO = "audio"
        internal const val KEY_SAVED_ID = "savedId"
        internal const val KEY_RECORDED_AT = "recordedAt"
        internal const val KEY_DURATION = "durationMs"
        internal const val KEY_PITCH_VOICED = "pitchVoicedMs"
        internal const val KEY_PITCH_MOST = "pitchMost"
        internal const val KEY_PITCH_LOW = "pitchLow"
        internal const val KEY_PITCH_HIGH = "pitchHigh"

        private const val SECTION_GONE =
            "Diesen Text gibt es nicht mehr in der Bibliothek. Entferne ihn oben, dann lässt sich die Aufnahme ohne Text speichern."
        private const val SECTION_FAILED =
            "Der gelesene Abschnitt ließ sich nicht übernehmen. Versuche es noch einmal oder entferne den Text, um ohne ihn zu speichern."
        private const val POSITION_LOST = "Die Stelle, bis zu der du gelesen hattest, war nicht mehr zu finden – der Text beginnt von vorne."
        private const val TOO_MANY_PAGES = "Mehr als ${TextAttachment.MAX_PAGES} Seiten passen nicht – die übrigen wurden nicht übernommen."

        /** Darunter lohnt keine Aufnahme mehr: etwa eine Minute. */
        private const val MIN_RECORDING_BYTES = FileStore.AUDIO_BYTES_PER_MINUTE

        /** Darunter endet eine laufende Aufnahme: Platz für den Abschluss der Datei und die Datenbank. */
        internal const val STOP_RECORDING_BYTES = FileStore.RESERVE_BYTES / 2
        private const val STORAGE_CHECK_MS = 1_000L

        val Factory = viewModelFactory {
            initializer {
                val app = app()
                RecordViewModel(app, app.container, createSavedStateHandle())
            }
        }
    }
}
