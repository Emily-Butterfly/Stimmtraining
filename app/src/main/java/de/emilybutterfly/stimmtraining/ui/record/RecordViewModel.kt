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
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.createDraftText
import de.emilybutterfly.stimmtraining.ui.getParams
import de.emilybutterfly.stimmtraining.ui.importCameraCapture
import de.emilybutterfly.stimmtraining.ui.importDraftDocument
import de.emilybutterfly.stimmtraining.ui.importDraftPhoto
import de.emilybutterfly.stimmtraining.ui.openWithChooser
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

data class RecordUiState(
    val params: RecordingParams = RecordingParams(),
    /** Der gelesene Text: Foto oder Datei. */
    val attachment: TextAttachment? = null,
    val attachmentBusy: Boolean = false,
    val phase: RecorderPhase = RecorderPhase.Idle,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val audio: File? = null,
    /** Datum und Uhrzeit des Aufnahmebeginns. */
    val recordedAt: Long? = null,
    val durationMs: Long = 0,
    val saving: Boolean = false,
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
        get() = attachment != null || attachmentBusy || phase != RecorderPhase.Idle || params != initialParams
}

/** Die Übung aus „Techniken & Übungen“, mit der diese Aufnahme gestartet wurde. */
data class ExerciseGuide(val label: String, val exercise: Exercise)

class RecordViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
    private val freeBytes: () -> Long = { container.files.freeBytes() },
) : ViewModel() {
    private val recorder = AudioRecorder(app)
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

    init {
        // Entwurf sichern, damit er überlebt, wenn Android die App im Hintergrund beendet
        // (z. B. während die Kamera-App offen ist).
        viewModelScope.launch {
            _state.map(::persistable).distinctUntilChanged().collect(::persist)
        }
        loadGuide()
    }

    /** Lädt die Übung, mit der die Aufnahme gestartet wurde, und trägt sie ins Formular ein. */
    private fun loadGuide() {
        val techniqueId = savedState.get<String>(ARG_TECHNIQUE) ?: return
        val exerciseId = savedState.get<String>(ARG_EXERCISE)
        viewModelScope.launch {
            val technique = withContext(Dispatchers.IO) { runCatching { container.techniques }.getOrNull() }
                ?.technique(techniqueId)
            val exercise = exerciseId?.let { technique?.exercise(it) }
            if (technique == null || exercise == null) {
                _guideReady.value = true
                return@launch
            }
            val guide = ExerciseGuide(technique.recordingLabel(exercise), exercise)
            _guide.value = guide
            _guideReady.value = true
            if (!hadSavedParams) {
                _state.update {
                    if (it.params.exercise.isNotBlank()) {
                        it
                    } else {
                        val params = it.params.copy(exercise = guide.label)
                        it.copy(params = params, initialParams = params)
                    }
                }
            }
        }
    }

    private data class Persistable(
        val params: RecordingParams,
        val initialParams: RecordingParams,
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
            photo = (state.attachment as? TextAttachment.Photo)?.file?.absolutePath,
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
        val params = savedState.getParams(KEY_PARAMS)
        val initialParams = savedState.getParams(KEY_INITIAL_PARAMS)
        val attachment = restoreAttachment()
        val audio = savedState.get<String>(KEY_AUDIO)?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
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
        savedState.get<String>(KEY_DOCUMENT)?.let(::File)?.takeIf { it.isFile }?.let { file ->
            return TextAttachment.Document(file, savedState.get<String>(KEY_DOCUMENT_NAME) ?: file.name)
        }
        return savedState.get<String>(KEY_PHOTO)?.let(::File)?.takeIf { it.isFile }?.let { TextAttachment.Photo(it) }
    }

    fun updateParams(params: RecordingParams) = _state.update { it.copy(params = params) }

    // --- Text: Foto oder Datei ---

    fun cameraTarget(): Uri = container.files.prepareCameraCapture()

    fun onCameraPhoto() = importAttachment(isPhoto = true) { TextAttachment.Photo(container.files.importCameraCapture(app)) }

    fun onPhotoPicked(uri: Uri) = importAttachment(isPhoto = true) { TextAttachment.Photo(container.files.importDraftPhoto(app, uri)) }

    fun onDocumentPicked(uri: Uri) = importAttachment(isPhoto = false) { container.files.importDraftDocument(app, uri) }

    fun onTextPasted(text: String, name: String?) {
        if (text.isBlank()) return
        importAttachment(isPhoto = false) { container.files.createDraftText(text, name) }
    }

    private fun importAttachment(isPhoto: Boolean, load: suspend () -> TextAttachment) {
        viewModelScope.launch {
            _state.update { it.copy(attachmentBusy = true) }
            try {
                val attachment = load()
                val previous = _state.value.attachment
                _state.update { it.copy(attachment = attachment, attachmentBusy = false) }
                previous?.file?.delete()
            } catch (e: Exception) {
                _state.update { it.copy(attachmentBusy = false, message = importFailure(isPhoto, e)) }
            }
        }
    }

    fun removeAttachment() {
        _state.value.attachment?.file?.delete()
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
                } catch (_: IllegalStateException) {
                    return
                }
                accumulatedMs += SystemClock.elapsedRealtime() - segmentStartedAt
                ticker?.cancel()
                _state.update { it.copy(phase = RecorderPhase.Paused, elapsedMs = accumulatedMs, level = 0f) }
            }
            RecorderPhase.Paused -> {
                try {
                    recorder.resume()
                } catch (_: IllegalStateException) {
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
        if (!usable) {
            file?.delete()
            _state.update {
                it.copy(
                    phase = RecorderPhase.Idle,
                    elapsedMs = 0,
                    level = 0f,
                    recordedAt = null,
                    message = "Die Aufnahme war zu kurz und wurde verworfen.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                phase = RecorderPhase.Done,
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
        if (current.phase != RecorderPhase.Done || pitchJob?.isActive == true) return
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
                _state.update { it.copy(message = "Die Tonhöhe ließ sich nicht analysieren: ${e.message}") }
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
            )
        }
    }

    /** Verwirft den ganzen Entwurf: Aufnahme und Text. */
    fun discardDraft() {
        discardRecording()
        removeAttachment()
    }

    fun save(onSaved: (Long) -> Unit) {
        val current = _state.value
        val audio = current.audio ?: return
        if (current.saving || current.attachmentBusy) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                // Eine laufende Analyse dauert nur Sekunden; ihr Ergebnis wird mitgespeichert.
                pitchJob?.join()
                val pitch = _state.value.pitch?.takeIf { _state.value.audio == audio }
                val id = withContext(NonCancellable) {
                    container.repository.create(
                        draftAudio = audio,
                        draftAttachment = current.attachment,
                        recordedAt = current.recordedAt ?: System.currentTimeMillis(),
                        durationMs = current.durationMs,
                        params = current.params,
                        pitch = pitch,
                    )
                }
                onSaved(id)
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
        internal const val KEY_PHOTO = "photo"
        internal const val KEY_DOCUMENT = "document"
        internal const val KEY_DOCUMENT_NAME = "documentName"
        internal const val KEY_AUDIO = "audio"
        internal const val KEY_RECORDED_AT = "recordedAt"
        internal const val KEY_DURATION = "durationMs"
        internal const val KEY_PITCH_VOICED = "pitchVoicedMs"
        internal const val KEY_PITCH_MOST = "pitchMost"
        internal const val KEY_PITCH_LOW = "pitchLow"
        internal const val KEY_PITCH_HIGH = "pitchHigh"

        /** Darunter lohnt keine Aufnahme mehr: etwa eine Minute. */
        private const val MIN_RECORDING_BYTES = FileStore.AUDIO_BYTES_PER_MINUTE

        /** Darunter endet eine laufende Aufnahme: Platz für den Abschluss der Datei und die Datenbank. */
        internal const val STOP_RECORDING_BYTES = FileStore.RESERVE_BYTES / 2
        private const val STORAGE_CHECK_MS = 1_000L

        private fun importFailure(isPhoto: Boolean, e: Exception): String = when {
            isPhoto -> "Foto konnte nicht übernommen werden: ${e.message}"
            e is IOException && !e.message.isNullOrBlank() -> e.message!!
            else -> "Die Datei konnte nicht übernommen werden."
        }

        val Factory = viewModelFactory {
            initializer {
                val app = app()
                RecordViewModel(app, app.container, createSavedStateHandle())
            }
        }
    }
}
