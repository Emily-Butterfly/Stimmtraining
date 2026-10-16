package de.emilybutterfly.stimmtraining.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import de.emilybutterfly.stimmtraining.MainActivity
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.RecordingParams
import kotlinx.coroutines.runBlocking
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

    /** Sucht auch Texte innerhalb anklickbarer Karten und Buttons. */
    private fun text(value: String) = compose.onNode(hasText(value), true)

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(text: String) =
        onAllNodes(hasText(text), true).fetchSemanticsNodes().isNotEmpty()
}
