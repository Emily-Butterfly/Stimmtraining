package de.emilybutterfly.stimmtraining.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.analysis.PitchResult
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.ui.record.RecordViewModel
import de.emilybutterfly.stimmtraining.ui.record.RecorderPhase
import de.emilybutterfly.stimmtraining.ui.record.StorageWarning
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Ein nicht gespeicherter Entwurf übersteht es, wenn Android die App beendet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<StimmtrainingApp>()

    @Test
    fun finishedTakePhotoAndParametersAreRestored() {
        val handle = SavedStateHandle()
        val params = RecordingParams(title = "Nordwind", warmedUp = true, warmUpMinutes = "5", selfRating = 3, notes = "hell")
        RecordViewModel(app, app.container, handle).updateParams(params)
        shadowOf(Looper.getMainLooper()).idle()

        // So sieht der gesicherte Zustand nach einer beendeten Aufnahme aus.
        val audio = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(100)) }
        val photo = app.container.files.newDraftFile("jpg").apply { writeBytes(ByteArray(10)) }
        handle[RecordViewModel.KEY_AUDIO] = audio.absolutePath
        handle[RecordViewModel.KEY_PHOTO] = photo.absolutePath
        handle[RecordViewModel.KEY_RECORDED_AT] = 1_791_000_000_000L
        handle[RecordViewModel.KEY_DURATION] = 42_000L

        val restored = RecordViewModel(app, app.container, handle).state.value
        assertEquals(RecorderPhase.Done, restored.phase)
        assertEquals(audio, restored.audio)
        assertEquals(TextAttachment.Photo(photo), restored.attachment)
        assertEquals(params, restored.params)
        assertEquals(1_791_000_000_000L, restored.recordedAt)
        assertEquals(42_000L, restored.durationMs)
    }

    @Test
    fun draftsSurviveTheViewModelBeingClearedAndAreOnlyDeletedWhenDiscarded() {
        val audio = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(100)) }
        val photo = app.container.files.newDraftFile("jpg").apply { writeBytes(ByteArray(10)) }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = audio.absolutePath
        handle[RecordViewModel.KEY_PHOTO] = photo.absolutePath

        // Android zerstört die Activity (ohne Konfigurationswechsel); der Zustand bleibt gesichert.
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { RecordViewModel(app, app.container, handle) } }
        ViewModelProvider(store, factory)[RecordViewModel::class.java]
        store.clear()
        assertTrue(audio.isFile && photo.isFile)

        val restored = RecordViewModel(app, app.container, handle)
        assertEquals(RecorderPhase.Done, restored.state.value.phase)
        restored.discardDraft()
        assertFalse(audio.exists())
        assertFalse(photo.exists())
    }

    @Test
    fun exercisePrefillIsNoDraftAlsoAfterRestore() {
        val twang = app.container.techniques.technique("twang")!!
        val exercise = twang.exercises.first()
        val handle = SavedStateHandle(
            mapOf(RecordViewModel.ARG_TECHNIQUE to twang.id, RecordViewModel.ARG_EXERCISE to exercise.id),
        )
        val first = RecordViewModel(app, app.container, handle)
        val deadline = System.currentTimeMillis() + 5_000
        while (first.state.value.params.exercise.isBlank() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(twang.recordingLabel(exercise), first.state.value.params.exercise)
        assertFalse("unverändertes Formular ist kein Entwurf", first.state.value.hasDraft)

        // Android beendet die App; dieselben gesicherten Werte kommen in ein neues ViewModel.
        val restored = RecordViewModel(app, app.container, handle).state.value
        assertEquals(twang.recordingLabel(exercise), restored.params.exercise)
        assertFalse("auch nach dem Wiederherstellen kein Entwurf", restored.hasDraft)
    }

    @Test
    fun documentDraftIsRestoredAndDeletedWhenDiscarded() {
        val document = app.container.files.newDraftFile("pdf").apply { writeBytes(ByteArray(10)) }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_DOCUMENT] = document.absolutePath
        handle[RecordViewModel.KEY_DOCUMENT_NAME] = "Nordwind und Sonne.pdf"

        val restored = RecordViewModel(app, app.container, handle)
        assertEquals(TextAttachment.Document(document, "Nordwind und Sonne.pdf"), restored.state.value.attachment)
        assertTrue("ein angehängter Text ist ein Entwurf", restored.state.value.hasDraft)
        restored.discardDraft()
        assertFalse(document.exists())
        assertNull(restored.state.value.attachment)
    }

    @Test
    fun lowStorageWarnsBeforeRecordingAndFullStorageBlocksIt() {
        var free = 30L * 1024 * 1024
        val viewModel = RecordViewModel(app, app.container, SavedStateHandle(), freeBytes = { free })

        viewModel.startRecording()
        val warning = viewModel.state.value.storageWarning
        assertEquals(StorageWarning(free, full = false), warning)
        assertEquals(21L, warning?.minutesLeft) // (30 MB − 10 MB Reserve) bei knapp 1 MB pro Minute
        assertEquals(RecorderPhase.Idle, viewModel.state.value.phase)
        viewModel.dismissStorageWarning()
        assertNull(viewModel.state.value.storageWarning)

        // Unter der Reserve hilft auch „Trotzdem aufnehmen“ nicht.
        free = 10L * 1024 * 1024 + 100_000
        viewModel.startRecording(ignoreLowStorage = true)
        assertEquals(true, viewModel.state.value.storageWarning?.full)
        assertEquals(RecorderPhase.Idle, viewModel.state.value.phase)
    }

    @Test
    fun withEnoughStorageRecordingStartsWithoutWarning() {
        val viewModel = RecordViewModel(app, app.container, SavedStateHandle(), freeBytes = { 4L shl 30 })
        viewModel.startRecording()
        assertNull(viewModel.state.value.storageWarning)
        assertEquals(RecorderPhase.Recording, viewModel.state.value.phase)
        viewModel.discardRecording()
        assertEquals(RecorderPhase.Idle, viewModel.state.value.phase)
    }

    @Test
    fun pastedTextBecomesADocumentNamedAfterItsFirstLine() {
        val handle = SavedStateHandle()
        val viewModel = RecordViewModel(app, app.container, handle)
        viewModel.onTextPasted("Der Nordwind und die Sonne\nEinst stritten sich …", name = null)
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.attachment == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val document = viewModel.state.value.attachment as TextAttachment.Document
        assertEquals("Der Nordwind und die Sonne", document.name)
        assertEquals("Der Nordwind und die Sonne\nEinst stritten sich …", document.file.readText())

        // Übersteht das Beenden der App wie jeder Entwurf.
        assertEquals(document, RecordViewModel(app, app.container, handle).state.value.attachment)
    }

    @Test
    fun recordingEndsWhenStorageRunsLowWhileRecording() {
        var free = 4L shl 30
        val viewModel = RecordViewModel(app, app.container, SavedStateHandle(), freeBytes = { free })
        viewModel.startRecording()
        assertEquals(RecorderPhase.Recording, viewModel.state.value.phase)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("genug Platz: läuft weiter", RecorderPhase.Recording, viewModel.state.value.phase)

        // Eine andere App füllt den Speicher.
        free = RecordViewModel.STOP_RECORDING_BYTES - 1
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(viewModel.state.value.isRecording)
    }

    @Test
    fun draftCreatedWhileTheCallerIsCancelledIsDeleted() = runBlocking {
        val files = app.container.files
        var draft: File? = null
        lateinit var job: Job
        job = launch {
            createDraft({ it: File -> it }) {
                files.newDraftFile("txt").apply { writeText("x") }.also {
                    draft = it
                    job.cancel() // z. B. Ansicht geschlossen, während die Datei kopiert wurde
                }
            }
        }
        job.join()
        assertFalse("verwaister Entwurf gelöscht", draft!!.exists())

        val kept = createDraft({ it: File -> it }) { files.newDraftFile("txt").apply { writeText("y") } }
        assertTrue(kept.isFile)
    }

    @Test
    fun pitchAnalysedBeforeSavingIsRestoredAndSaved() {
        val audio = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(100)) }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = audio.absolutePath
        handle[RecordViewModel.KEY_DURATION] = 30_000L
        handle[RecordViewModel.KEY_PITCH_VOICED] = 12_000L
        handle[RecordViewModel.KEY_PITCH_MOST] = 57
        handle[RecordViewModel.KEY_PITCH_LOW] = 52
        handle[RecordViewModel.KEY_PITCH_HIGH] = 62

        val viewModel = RecordViewModel(app, app.container, handle)
        assertEquals(PitchResult(57, 52, 62, 12_000), viewModel.state.value.pitch)

        var savedId: Long? = null
        viewModel.save { savedId = it }
        val deadline = System.currentTimeMillis() + 5_000
        while (savedId == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val saved = runBlocking { app.container.repository.observe(savedId!!).first() }!!
        assertEquals(57, saved.pitchMostFrequent)
        assertEquals(52, saved.pitchLowest)
        assertEquals(62, saved.pitchHighest)
        assertEquals(12_000L, saved.pitchVoicedMs)
    }

    @Test
    fun retakeForgetsThePitchOfTheOldTake() {
        val audio = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(100)) }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = audio.absolutePath
        handle[RecordViewModel.KEY_PITCH_VOICED] = 12_000L
        handle[RecordViewModel.KEY_PITCH_MOST] = 57
        handle[RecordViewModel.KEY_PITCH_LOW] = 52
        handle[RecordViewModel.KEY_PITCH_HIGH] = 62

        val viewModel = RecordViewModel(app, app.container, handle)
        viewModel.discardRecording()
        assertNull(viewModel.state.value.pitch)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("auch nicht im gesicherten Zustand", RecordViewModel(app, app.container, handle).state.value.pitch)
    }

    @Test
    fun exerciseGuideFollowsGentleModeWhileOnTheRecordScreen() {
        val handle = SavedStateHandle(
            mapOf(RecordViewModel.ARG_TECHNIQUE to "tonhoehe", RecordViewModel.ARG_EXERCISE to "tonhoehe-ausgangslage"),
        )
        val viewModel = RecordViewModel(app, app.container, handle)
        fun waitFor(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        waitFor { viewModel.guide.value != null }
        val label = viewModel.state.value.params.exercise
        assertTrue(viewModel.guide.value!!.exercise.steps.any { "Tonhöhen-App" in it })

        app.container.settings.setGentleMode(true)
        waitFor { viewModel.guide.value!!.exercise.steps.none { "Tonhöhen-App" in it } }
        assertTrue("sanfte Fassung", viewModel.guide.value!!.exercise.steps.none { "Tonhöhen-App" in it || "Hz" in it })
        assertEquals("Übung im Formular bleibt", label, viewModel.state.value.params.exercise)
        app.container.settings.setGentleMode(false)
    }

    @Test
    fun missingDraftFilesAreIgnored() {
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = "/nicht/vorhanden.m4a"
        handle[RecordViewModel.KEY_PHOTO] = "/nicht/vorhanden.jpg"
        handle[RecordViewModel.KEY_DOCUMENT] = "/nicht/vorhanden.pdf"

        val restored = RecordViewModel(app, app.container, handle).state.value
        assertEquals(RecorderPhase.Idle, restored.phase)
        assertNull(restored.audio)
        assertNull(restored.attachment)
    }
}
