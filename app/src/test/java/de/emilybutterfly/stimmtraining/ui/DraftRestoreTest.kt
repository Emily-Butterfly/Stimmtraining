package de.emilybutterfly.stimmtraining.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.ui.record.RecordViewModel
import de.emilybutterfly.stimmtraining.ui.record.RecorderPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
