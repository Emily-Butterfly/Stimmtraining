package de.emilybutterfly.stimmtraining.ui.record

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.audio.AudioRecorder
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.getParams
import de.emilybutterfly.stimmtraining.ui.importCameraCapture
import de.emilybutterfly.stimmtraining.ui.importDraftPhoto
import de.emilybutterfly.stimmtraining.ui.prepareCameraCapture
import de.emilybutterfly.stimmtraining.ui.putParams
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
import kotlin.math.max

enum class RecorderPhase { Idle, Recording, Paused, Done }

data class RecordUiState(
    val params: RecordingParams = RecordingParams(),
    val photo: File? = null,
    val photoBusy: Boolean = false,
    val phase: RecorderPhase = RecorderPhase.Idle,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val audio: File? = null,
    /** Datum und Uhrzeit des Aufnahmebeginns. */
    val recordedAt: Long? = null,
    val durationMs: Long = 0,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val isRecording: Boolean get() = phase == RecorderPhase.Recording || phase == RecorderPhase.Paused

    val hasDraft: Boolean
        get() = photo != null || phase != RecorderPhase.Idle || params != RecordingParams()
}

class RecordViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val recorder = AudioRecorder(app)
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private var recordingFile: File? = null
    private var accumulatedMs = 0L
    private var segmentStartedAt = 0L
    private var ticker: Job? = null
    private var saved = false

    init {
        // Entwurf sichern, damit er überlebt, wenn Android die App im Hintergrund beendet
        // (z. B. während die Kamera-App offen ist).
        viewModelScope.launch {
            _state.map(::persistable).distinctUntilChanged().collect(::persist)
        }
    }

    private data class Persistable(
        val params: RecordingParams,
        val photo: String?,
        val audio: String?,
        val recordedAt: Long?,
        val durationMs: Long,
    )

    private fun persistable(state: RecordUiState): Persistable {
        val done = state.phase == RecorderPhase.Done
        return Persistable(
            params = state.params,
            photo = state.photo?.absolutePath,
            audio = if (done) state.audio?.absolutePath else null,
            recordedAt = if (done) state.recordedAt else null,
            durationMs = if (done) state.durationMs else 0,
        )
    }

    private fun persist(p: Persistable) {
        savedState.putParams(KEY_PARAMS, p.params)
        savedState[KEY_PHOTO] = p.photo
        savedState[KEY_AUDIO] = p.audio
        savedState[KEY_RECORDED_AT] = p.recordedAt
        savedState[KEY_DURATION] = p.durationMs
    }

    private fun restore(): RecordUiState {
        val params = savedState.getParams(KEY_PARAMS)
        val photo = savedState.get<String>(KEY_PHOTO)?.let(::File)?.takeIf { it.isFile }
        val audio = savedState.get<String>(KEY_AUDIO)?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
            ?: return RecordUiState(params = params, photo = photo)
        val duration = savedState[KEY_DURATION] ?: 0L
        return RecordUiState(
            params = params,
            photo = photo,
            phase = RecorderPhase.Done,
            audio = audio,
            recordedAt = savedState[KEY_RECORDED_AT],
            durationMs = duration,
            elapsedMs = duration,
        )
    }

    fun updateParams(params: RecordingParams) = _state.update { it.copy(params = params) }

    // --- Foto ---

    fun cameraTarget(): Uri = container.files.prepareCameraCapture()

    fun onCameraPhoto() = importPhoto { container.files.importCameraCapture(app) }

    fun onPhotoPicked(uri: Uri) = importPhoto { container.files.importDraftPhoto(app, uri) }

    private fun importPhoto(load: suspend () -> File) {
        viewModelScope.launch {
            _state.update { it.copy(photoBusy = true) }
            try {
                val photo = load()
                val previous = _state.value.photo
                _state.update { it.copy(photo = photo, photoBusy = false) }
                previous?.delete()
            } catch (e: Exception) {
                _state.update {
                    it.copy(photoBusy = false, message = "Foto konnte nicht übernommen werden: ${e.message}")
                }
            }
        }
    }

    fun removePhoto() {
        _state.value.photo?.delete()
        _state.update { it.copy(photo = null) }
    }

    // --- Aufnahme ---

    fun startRecording() {
        if (_state.value.phase != RecorderPhase.Idle || recorder.isActive) return
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
        if (!ok || file == null || !file.isFile || file.length() == 0L) {
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

    fun discardRecording() {
        ticker?.cancel()
        if (recorder.isActive) recorder.stop()
        recordingFile?.delete()
        recordingFile = null
        _state.value.audio?.delete()
        _state.update {
            it.copy(phase = RecorderPhase.Idle, audio = null, elapsedMs = 0, durationMs = 0, recordedAt = null, level = 0f)
        }
    }

    fun save(onSaved: (Long) -> Unit) {
        val current = _state.value
        val audio = current.audio ?: return
        if (current.saving || current.photoBusy) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val id = withContext(NonCancellable) {
                    container.repository.create(
                        draftAudio = audio,
                        draftPhoto = current.photo,
                        recordedAt = current.recordedAt ?: System.currentTimeMillis(),
                        durationMs = current.durationMs,
                        params = current.params,
                    )
                }
                saved = true
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
            while (isActive) {
                val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - segmentStartedAt)
                val level = recorder.level()
                _state.update { it.copy(elapsedMs = elapsed, level = max(level, it.level * 0.8f)) }
                delay(80)
            }
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        if (recorder.isActive) recorder.stop()
        if (!saved) {
            recordingFile?.delete()
            _state.value.audio?.delete()
            _state.value.photo?.delete()
        }
    }

    companion object {
        internal const val KEY_PARAMS = "params"
        internal const val KEY_PHOTO = "photo"
        internal const val KEY_AUDIO = "audio"
        internal const val KEY_RECORDED_AT = "recordedAt"
        internal const val KEY_DURATION = "durationMs"

        val Factory = viewModelFactory {
            initializer {
                val app = app()
                RecordViewModel(app, app.container, createSavedStateHandle())
            }
        }
    }
}
