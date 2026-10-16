package de.emilybutterfly.stimmtraining.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import de.emilybutterfly.stimmtraining.MainActivity
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.RecordingParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Startet die echte App und öffnet jede Ansicht einmal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSmokeTest {
    // Auf dem Gerät startet AndroidX Startup den WorkManager; Robolectric tut das nicht.
    @get:Rule(order = 0)
    val workManager = object : ExternalResource() {
        override fun before() {
            WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun allScreensOpen() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("Noch keine Aufnahmen") }

        text("Neue Aufnahme").performClick()
        text("Tippen, um die Aufnahme zu starten").assertExists()
        text("Vorher aufgewärmt").performScrollTo().assertExists()
        text("Foto vom Text").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Zurück").performClick()

        compose.onNodeWithContentDescription("Backup").performClick()
        text("Sicherung als Datei").assertExists()
        text("Automatisches Backup").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Zurück").performClick()

        // Eine gespeicherte Aufnahme erscheint in der Liste und lässt sich öffnen und bearbeiten.
        val container = ApplicationProvider.getApplicationContext<StimmtrainingApp>().container
        runBlocking {
            val audio = container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(10)) }
            container.repository.create(
                audio,
                null,
                System.currentTimeMillis(),
                65_000,
                RecordingParams(title = "Nordwind", warmedUp = true, warmUpMinutes = "10", selfRating = 4),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("Nordwind") }
        text("aufgewärmt").assertExists()
        text("Nordwind").performClick()
        text("Ja, 10 Minuten").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Bearbeiten").performClick()
        text("Änderungen speichern").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Abbrechen").performClick()
        compose.onNodeWithContentDescription("Teilen").assertIsDisplayed()
    }

    @Test
    fun exerciseCanBeRecordedFromTechniques() {
        val content = ApplicationProvider.getApplicationContext<StimmtrainingApp>().container.techniques
        val twang = content.technique("twang")!!
        val firstExercise = twang.exercises.first()

        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("Noch keine Aufnahmen") }
        compose.onNodeWithContentDescription("Techniken & Übungen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists(twang.title) }
        text("Schwerpunkt").assertExists()
        text(twang.title).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists("Warum es hilft") }
        // Die Liste ist lazy: erst zur ersten Übung scrollen.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(firstExercise.title))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Mit dieser Übung aufnehmen"))
        compose.onAllNodes(hasText("Mit dieser Übung aufnehmen"), true)[0].performClick()

        // Die Aufnahme zeigt die Anleitung und hat die Übung schon eingetragen.
        val label = twang.recordingLabel(firstExercise)
        compose.waitUntil(5_000) { compose.onAllNodesWithTextExists(label) }
        compose.onAllNodes(hasText(label), true).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        text("Tippen, um die Aufnahme zu starten").assertExists()
    }

    /** Sucht auch Texte innerhalb anklickbarer Karten und Buttons. */
    private fun text(value: String) = compose.onNode(hasText(value), true)

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(text: String) =
        onAllNodes(hasText(text), true).fetchSemanticsNodes().isNotEmpty()
}
