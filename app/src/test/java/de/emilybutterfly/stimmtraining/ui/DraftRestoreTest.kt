package de.emilybutterfly.stimmtraining.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.ui.record.RecordViewModel
import de.emilybutterfly.stimmtraining.ui.record.RecorderPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
        assertEquals(photo, restored.photo)
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
    fun missingDraftFilesAreIgnored() {
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = "/nicht/vorhanden.m4a"
        handle[RecordViewModel.KEY_PHOTO] = "/nicht/vorhanden.jpg"

        val restored = RecordViewModel(app, app.container, handle).state.value
        assertEquals(RecorderPhase.Idle, restored.phase)
        assertNull(restored.audio)
        assertNull(restored.photo)
    }
}
