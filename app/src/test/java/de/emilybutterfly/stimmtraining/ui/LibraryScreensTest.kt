package de.emilybutterfly.stimmtraining.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.activity.ComponentActivity
import de.emilybutterfly.stimmtraining.MainActivity
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.ui.components.PageOverviewDialog
import de.emilybutterfly.stimmtraining.ui.library.LibraryScreen
import de.emilybutterfly.stimmtraining.ui.library.LibraryTextScreen
import de.emilybutterfly.stimmtraining.ui.record.RecordScreen
import de.emilybutterfly.stimmtraining.ui.record.RecordViewModel
import de.emilybutterfly.stimmtraining.ui.theme.StimmtrainingTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Die Bibliothek in der echten App bedienen; Bildschirmfotos nur mit STIMM_SCREENSHOT_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class LibraryAppTest {
    @get:Rule(order = 0)
    val workManager = object : ExternalResource() {
        override fun before() {
            WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<StimmtrainingApp>()
    private val library get() = app.container.library

    @Before
    fun empty() = runBlocking { library.all().forEach { library.delete(it.id) } }

    private fun text(value: String, substring: Boolean = false) = compose.onNode(hasText(value, substring = substring), true)

    private fun waitForText(value: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(value, substring = substring), true).fetchSemanticsNodes().isNotEmpty() }

    private fun screenshot(name: String) {
        val dir = System.getenv("STIMM_SCREENSHOT_DIR") ?: return
        compose.waitForIdle()
        Thread.sleep(300) // Bilder laden im Hintergrund
        compose.waitForIdle()
        // Dialoge haben ein eigenes Fenster: das oberste aufnehmen.
        val view = topWindow() ?: compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun topWindow(): android.view.View? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        views.filterIsInstance<android.view.View>().lastOrNull { it.isShown && it.width > 0 }
    }.getOrNull()

    /** Bei einem Fehler zeigt ein Bildschirmfoto, wo der Test stand. */
    private fun <T> withFailureShot(name: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        screenshot("fehler_$name")
        throw e
    }

    /** Eine „fotografierte Buchseite“: weiß mit dunklen Zeilen. */
    private fun page(number: Int): File {
        val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 248, 240))
        val paint = Paint().apply { color = Color.rgb(40, 40, 40) }
        for (line in 0 until 22) {
            val top = 60f + line * 34f
            canvas.drawRect(50f, top, 550f - (line * 37 + number * 53) % 140, top + 14f, paint)
        }
        paint.textSize = 40f
        canvas.drawText("Seite $number", 230f, 820f, paint)
        return app.container.files.newDraftFile("jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    @Test
    fun aPastedTextGoesIntoTheLibraryAndCanBeChosenForARecording() = withFailureShot("einfuegen") {
        waitForText("Stimmtraining")
        compose.onNodeWithContentDescription("Bibliothek").performClick()
        waitForText("Noch keine Texte")
        text("Text hinzufügen").performClick()
        text("Einfügen").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(
            "Der Nordwind und die Sonne\nEinst stritten sich der Nordwind und die Sonne, wer von ihnen beiden wohl der Stärkere wäre.",
        )
        text("Übernehmen").performClick()
        // Der Titel kommt aus der ersten Zeile.
        waitForText("Der Nordwind und die Sonne")
        compose.onNode(hasSetTextAction() and hasText("z. B. Gedicht, Zeitung, Dialog")).performTextInput("Fabel")
        compose.onNode(hasSetTextAction() and hasText("Fabel")).performImeAction()
        text("In die Bibliothek legen").performScrollTo().performClick()

        waitForText("Zu lesen (1)")
        text("Textdatei · Noch nicht begonnen").assertExists()
        val saved = runBlocking { library.all().single() }
        assertEquals("Der Nordwind und die Sonne", saved.title)
        assertEquals(listOf("Fabel"), saved.tagList)
        screenshot("bibliothek_liste")

        // Beim Aufnehmen auswählen.
        compose.onNodeWithContentDescription("Zurück").performClick()
        text("Neue Aufnahme").performClick()
        text("Aus Bibliothek").performScrollTo().performClick()
        waitForText("Text auswählen")
        waitForText("Textdatei · Noch nicht begonnen")
        text("Der Nordwind und die Sonne").performClick()
        waitForText("Aus der Bibliothek")
        text("Tippe beim oder nach dem Lesen auf die Stelle, bis zu der du kommst.").assertExists()
        screenshot("aufnahme_mit_bibliothekstext")
    }

    @Test
    fun theLibraryFiltersByTabSearchAndTag() {
        runBlocking {
            val a = library.add(TextAttachment.Document(draftText("Ein Gedicht über den Herbst."), "Herbst"), "Herbst", listOf("Gedicht"), move = true)
            library.add(TextAttachment.Document(draftText("Die Zeitung von heute."), "Zeitung"), "Zeitung", listOf("Zeitung"), move = true)
            val c = library.add(TextAttachment.Photo(listOf(page(1), page(2), page(3))), "Buchkapitel", listOf("Prosa", "Gedicht"), move = true)
            val names = library.get(c)!!.photoPages
            library.recordReading(c, ReadingPosition.OnPage(1, 0.5f, names[1]), 0.5f)
            library.setPinned(c, true)
            library.recordReading(a, end = null, fraction = null)
        }
        waitForText("Stimmtraining")
        compose.onNodeWithContentDescription("Bibliothek").performClick()
        waitForText("Zu lesen (2)")
        text("Gelesen (1)").assertExists()
        text("3 Seiten · Etwa 50 % gelesen").assertExists()
        screenshot("bibliothek_mit_texten")

        // Schlagwort „Prosa“: nur das Buchkapitel.
        // Der Filter-Chip steht vor den Schlagworten auf den Karten.
        compose.onAllNodes(hasText("Prosa"), true)[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Zeitung"), true).fetchSemanticsNodes().size == 1 } // nur noch der Filter-Chip
        compose.onAllNodes(hasText("Prosa"), true)[0].performClick()

        // Suche im Text.
        compose.onNode(hasSetTextAction()).performTextInput("zeitung von")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Buchkapitel"), true).fetchSemanticsNodes().isEmpty() }

        // „Gelesen“ zeigt den fertig gelesenen Text.
        text("Gelesen (1)").performClick()
        compose.onNodeWithContentDescription("Suche leeren").performClick()
        waitForText("Herbst")
    }

    private fun draftText(content: String) = app.container.files.newDraftFile("txt").apply { writeText(content) }
}

/** Einzelne Ansichten der Bibliothek und des Weiterlesens; Bildschirmfotos nur mit STIMM_SCREENSHOT_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class LibraryScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<StimmtrainingApp>()
    private val library get() = app.container.library

    @Before
    fun empty() = runBlocking { library.all().forEach { library.delete(it.id) } }

    private fun text(value: String, substring: Boolean = false) = compose.onNode(hasText(value, substring = substring), true)

    private fun waitForText(value: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(value, substring = substring), true).fetchSemanticsNodes().isNotEmpty() }

    private fun screenshot(name: String) {
        val dir = System.getenv("STIMM_SCREENSHOT_DIR") ?: return
        compose.waitForIdle()
        Thread.sleep(300) // Bilder laden im Hintergrund
        compose.waitForIdle()
        // Dialoge haben ein eigenes Fenster: das oberste aufnehmen.
        val view = topWindow() ?: compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun topWindow(): android.view.View? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        views.filterIsInstance<android.view.View>().lastOrNull { it.isShown && it.width > 0 }
    }.getOrNull()

    /** Bei einem Fehler zeigt ein Bildschirmfoto, wo der Test stand. */
    private fun <T> withFailureShot(name: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        screenshot("fehler_$name")
        throw e
    }

    /** Eine „fotografierte Buchseite“: weiß mit dunklen Zeilen. */
    private fun page(number: Int): File {
        val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 248, 240))
        val paint = Paint().apply { color = Color.rgb(40, 40, 40) }
        for (line in 0 until 22) {
            val top = 60f + line * 34f
            canvas.drawRect(50f, top, 550f - (line * 37 + number * 53) % 140, top + 14f, paint)
        }
        paint.textSize = 40f
        canvas.drawText("Seite $number", 230f, 820f, paint)
        return app.container.files.newDraftFile("jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    @Test
    fun aTextShowsItsProgressAndOffersToContinue() {
        val id = runBlocking {
            val story = "Erster Satz. Zweiter Satz. Dritter Satz.\n\nVierter Satz. Fünfter Satz."
            val id = library.add(TextAttachment.Document(draftText(story), "Geschichte"), "Geschichte", listOf("Prosa"), move = true)
            library.recordReading(id, ReadingPosition.InText(story.indexOf("Zweiter Satz.") + 13, "Zweiter Satz."), 0.36f)
            id
        }
        compose.setContent {
            StimmtrainingTheme {
                LibraryTextScreen(textId = id, onClose = {}, onRecord = {})
            }
        }
        waitForText("Etwa 36 % gelesen")
        text("Zuletzt gelesen: „Zweiter Satz.“").assertExists()
        text("Weiterlesen und aufnehmen").assertExists()
        screenshot("bibliothek_text_fortschritt")

        // Stelle von Hand korrigieren: das letzte Absatzende antippen.
        text("Stelle ändern").performClick()
        waitForText("Bis wohin gelesen?")
        compose.onNode(hasText("Vierter Satz. Fünfter Satz.") and hasAnyAncestor(isDialog())).performTouchInput { click(centerRight.copy(x = right - 2f)) }
        screenshot("bibliothek_stelle_aendern")
        text("Übernehmen").performClick()
        // Der letzte Satz ist das Ende: Der Text gilt damit als gelesen und wandert nach „Gelesen“.
        compose.waitUntil(5_000) { runBlocking { library.get(id)!!.finishedAt } != null }
        assertEquals(null, runBlocking { library.get(id)!!.progress })
    }

    @Test
    fun recordingWithAPartlyReadTextShowsOnlyTheRestAndAsksHowFar() {
        val story = "Erster Satz. Zweiter Satz. Dritter Satz.\n\nVierter Satz. Fünfter Satz.\n\nSechster Satz."
        val id = runBlocking {
            val id = library.add(TextAttachment.Document(draftText(story), "Geschichte"), "Geschichte", move = true)
            library.recordReading(id, ReadingPosition.InText(story.indexOf("Zweiter Satz.") + 13, "Zweiter Satz."), 0.3f)
            id
        }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = app.container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(100)) }.absolutePath
        handle[RecordViewModel.KEY_DURATION] = 30_000L
        handle[RecordViewModel.ARG_LIBRARY_TEXT] = id
        val viewModel = RecordViewModel(app, app.container, handle)
        compose.setContent {
            StimmtrainingTheme {
                RecordScreen(onClose = {}, onSaved = {}, viewModel = viewModel)
            }
        }
        waitForText("Wie weit hast du gelesen?")
        text("Es geht weiter, wo du aufgehört hast; blass ist das zuletzt Gelesene.").assertExists()
        // Der erste Satz ist ausgeblendet, der zweite blass, ab dem dritten geht es weiter.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Zweiter Satz. Dritter Satz."), true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Erster Satz.", substring = true), true).fetchSemanticsNodes().isEmpty())

        // Den vierten Absatz am Ende antippen: gelesen bis „Fünfter Satz.“
        text("Vierter Satz. Fünfter Satz.").performTouchInput { click(centerRight.copy(x = right - 2f)) }
        compose.waitUntil(5_000) { viewModel.state.value.library?.end != null }
        assertEquals("Fünfter Satz.", (viewModel.state.value.library!!.end as ReadingPosition.InText).anchor)
        text("Gelesen bis „Fünfter Satz.“ – nächstes Mal geht es dort weiter.").assertExists()
        screenshot("aufnahme_weiterlesen_markiert")

        text("Bis zum Ende").performClick()
        compose.waitUntil(5_000) { viewModel.state.value.library?.end == null }
    }

    @Test
    fun photoPagesShowTheReadPartAndCanBeArranged() {
        val id = runBlocking {
            val id = library.add(TextAttachment.Photo(listOf(page(1), page(2), page(3))), "Buchkapitel", move = true)
            val names = library.get(id)!!.photoPages
            library.recordReading(id, ReadingPosition.OnPage(1, 0.4f, names[1]), 0.45f)
            id
        }
        val handle = SavedStateHandle()
        handle[RecordViewModel.ARG_LIBRARY_TEXT] = id
        val viewModel = RecordViewModel(app, app.container, handle)
        compose.setContent {
            StimmtrainingTheme {
                RecordScreen(onClose = {}, onSaved = {}, viewModel = viewModel)
            }
        }
        waitForText("Aus der Bibliothek")
        // Weiter auf Seite 2 von 3; die erste ist ausgeblendet, gezählt wird trotzdem ab dem Anfang.
        waitForText("2 / 3")
        screenshot("aufnahme_fotoseiten_weiterlesen")
    }

    @Test
    fun thePageOverviewOffersMovingRotatingAndDeleting() {
        val pages = listOf(page(1), page(2))
        compose.setContent {
            StimmtrainingTheme {
                PageOverviewDialog(pages = pages, busy = false, onRotate = { _, _ -> }, onMove = { _, _ -> }, onDelete = {}, onAddCamera = {}, onAddGallery = {}, onDismiss = {})
            }
        }
        waitForText("2 Seiten")
        compose.onNodeWithContentDescription("Seite 2 nach links drehen").assertExists()
        compose.onNodeWithContentDescription("Seite 1 nach vorne").assertExists()
        screenshot("seitenuebersicht")
    }

    @Test
    fun theLibraryListPickerReturnsTheChosenText() {
        val id = runBlocking { library.add(TextAttachment.Document(draftText("Kurz."), "Kurz"), "Kurz", move = true) }
        var picked: Long? = null
        compose.setContent {
            StimmtrainingTheme {
                LibraryScreen(onClose = {}, onOpenText = {}, onAddText = {}, onPick = { picked = it })
            }
        }
        waitForText("Text auswählen")
        text("Kurz").performClick()
        assertEquals(id, picked)
        assertNotNull(runBlocking { library.observeAll().first() })
    }

    private fun draftText(content: String) = app.container.files.newDraftFile("txt").apply { writeText(content) }
}
