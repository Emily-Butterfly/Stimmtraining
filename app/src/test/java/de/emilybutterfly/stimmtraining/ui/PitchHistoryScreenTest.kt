package de.emilybutterfly.stimmtraining.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.analysis.PitchResult
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.ui.pitch.PitchHistoryScreen
import de.emilybutterfly.stimmtraining.ui.pitch.PitchHistoryViewModel
import de.emilybutterfly.stimmtraining.ui.theme.StimmtrainingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Der Tonhöhen-Verlauf mit Beispieldaten; Bildschirmfotos auf Wunsch (STIMM_SCREENSHOT_DIR). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class PitchHistoryScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<StimmtrainingApp>()
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_791_000_000_000L

    private fun seed() = runBlocking {
        // Acht Wochen Training: die häufigste Note steigt langsam von F#3 auf A3.
        val most = listOf(54, 54, 55, 55, 56, 55, 57, 56, 57, 57)
        most.forEachIndexed { i, note ->
            val audio = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(10)) }
            val exercise = if (i % 3 == 2) "Twang: Ng-Siren" else "Text lesen"
            val id = app.container.repository.create(audio, null, now - (56 - i * 6) * day, 60_000, RecordingParams(exercise = exercise))
            app.container.repository.savePitch(id, PitchResult(note, note - 4 - i % 2, note + 5 + i % 3, 20_000))
        }
    }

    private fun show(dark: Boolean = false, waitFor: String = "Häufigste Note je Aufnahme") {
        val viewModel = PitchHistoryViewModel(app.container.repository, SavedStateHandle(), now = { now })
        compose.setContent {
            StimmtrainingTheme(darkTheme = dark) {
                PitchHistoryScreen(onClose = {}, onOpenRecording = {}, viewModel = viewModel)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(waitFor)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val dir = System.getenv("STIMM_SCREENSHOT_DIR") ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun chartFiltersAndTableShowTheAnalysedRecordings() {
        seed()
        show()
        screenshot("verlauf_hell")
        // Neueste Aufnahme ist ausgewählt; die Tabelle nennt jede Aufnahme mit tiefster · häufigster · höchster Note.
        assertEquals("Auswahl und Tabellenzeile", 2, compose.onAllNodes(hasText("E3 · A3 · D4")).fetchSemanticsNodes().size)

        // Nur die Twang-Übung: 3 der 10 Aufnahmen.
        // Filterchip, Auswahl im Diagramm und je eine Tabellenzeile pro Aufnahme.
        fun twang() = compose.onAllNodes(hasText("Twang: Ng-Siren")).fetchSemanticsNodes().size
        compose.onAllNodes(hasText("Twang: Ng-Siren"))[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { twang() == 1 + 1 + 3 }
        screenshot("verlauf_twang")

        // Davon in den letzten 4 Wochen: zwei.
        compose.onNode(hasText("4 Wochen")).performScrollTo().performClick()
        compose.waitUntil(5_000) { twang() == 1 + 1 + 2 }
    }

    @Test
    fun darkMode() {
        seed()
        show(dark = true)
        screenshot("verlauf_dunkel")
    }

    @Test
    fun hugeRangesFromDamagedBackupsDoNotCrashTheChart() {
        runBlocking {
            val audio = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(10)) }
            val id = app.container.repository.create(audio, null, now, 60_000, RecordingParams(exercise = "Text lesen"))
            app.container.repository.savePitch(id, PitchResult(50, 10, 110, 20_000))
        }
        show()
    }

    @Test
    fun emptyStateExplainsHowToGetData() {
        show(waitFor = "Noch keine analysierten Aufnahmen")
    }
}
