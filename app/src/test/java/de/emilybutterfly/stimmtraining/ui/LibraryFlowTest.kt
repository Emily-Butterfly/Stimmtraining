package de.emilybutterfly.stimmtraining.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.AttachmentChange
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import de.emilybutterfly.stimmtraining.data.document.DocumentText
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import de.emilybutterfly.stimmtraining.data.library.TextLayout
import de.emilybutterfly.stimmtraining.data.library.TextProgress
import de.emilybutterfly.stimmtraining.ui.record.RecordViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Einen Text aus der Bibliothek über mehrere Aufnahmen hinweg lesen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryFlowTest {
    private val app = ApplicationProvider.getApplicationContext<StimmtrainingApp>()
    private val container get() = app.container
    private val library get() = container.library

    private val story = "Erster Satz. Zweiter Satz. Dritter Satz.\n\nVierter Satz. Fünfter Satz."

    @Before
    fun cleanUp() = runBlocking {
        library.all().forEach { library.delete(it.id) }
        container.repository.observeAll().first().forEach { container.repository.delete(it) }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Zeitüberschreitung", condition())
    }

    /** Eine Aufnahme, die gerade fertig ist (so, wie sie nach dem Stoppen gesichert wird). */
    private fun finishedTake(textId: Long? = null): Pair<RecordViewModel, SavedStateHandle> {
        val audio = container.files.newDraftFile("m4a").apply { writeBytes(ByteArray(100)) }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_AUDIO] = audio.absolutePath
        handle[RecordViewModel.KEY_DURATION] = 30_000L
        handle[RecordViewModel.KEY_RECORDED_AT] = 1_791_000_000_000L
        if (textId != null) handle[RecordViewModel.ARG_LIBRARY_TEXT] = textId
        val viewModel = RecordViewModel(app, container, handle)
        if (textId != null) waitFor { viewModel.state.value.library != null && !viewModel.state.value.attachmentBusy }
        return viewModel to handle
    }

    private fun save(viewModel: RecordViewModel): Long {
        var saved: Long? = null
        viewModel.save { saved = it }
        waitFor { saved != null }
        return saved!!
    }

    private fun addStory(): Long = runBlocking {
        library.add(TextAttachment.Document(container.files.newDraftFile("txt").apply { writeText(story) }, "Geschichte"), "Geschichte", move = true)
    }

    private fun after(sentence: String): ReadingPosition.InText {
        val text = library.attachmentOf(runBlocking { library.get(library.all().single().id)!! }) as TextAttachment.Document
        val layout = TextLayout(DocumentText.read(text.file, text.kind))
        val start = layout.document.plainText.indexOf(sentence)
        return TextProgress.after(layout, layout.sentenceAt(start)!!)
    }

    private fun sectionText(recordingId: Long): String {
        val recording = runBlocking { container.repository.observe(recordingId).first()!! }
        val attachment = container.repository.attachmentOf(recording) as TextAttachment.Document
        return DocumentText.read(attachment.file, attachment.kind).plainText
    }

    @Test
    fun aLongTextIsReadOverTwoRecordingsAndThenMovesToRead() {
        val id = addStory()

        // Erste Aufnahme: bis „Zweiter Satz.“ gelesen.
        val (first, _) = finishedTake(id)
        assertNull("beginnt von vorne", first.state.value.library!!.start)
        val untilSecond = after("Zweiter Satz.")
        first.markLibraryEnd(untilSecond)
        val firstRecording = save(first)
        assertEquals("Erster Satz. Zweiter Satz.", sectionText(firstRecording))
        val afterFirst = runBlocking { library.get(id)!! }
        assertEquals(untilSecond, afterFirst.progress)
        assertFalse(afterFirst.isFinished)
        assertTrue(afterFirst.progressFraction!! in 0.2f..0.5f)

        // Zweite Aufnahme: weiter ab dem dritten Satz, bis zum Ende.
        val (second, _) = finishedTake(id)
        assertEquals(untilSecond, second.state.value.library!!.start)
        assertNull("„Bis zum Ende“ ist vorausgewählt", second.state.value.library!!.end)
        val secondRecording = save(second)
        assertEquals("Dritter Satz.\n\nVierter Satz. Fünfter Satz.", sectionText(secondRecording))
        val finished = runBlocking { library.get(id)!! }
        assertNotNull("wandert nach „Gelesen“", finished.finishedAt)
        assertNull(finished.progress)

        // Die erste Aufnahme hat ihren Abschnitt noch, auch wenn der Text gelöscht wird.
        runBlocking { library.delete(id) }
        assertEquals("Erster Satz. Zweiter Satz.", sectionText(firstRecording))
    }

    @Test
    fun tappingTheLastSentenceCountsAsReadToTheEnd() {
        val id = addStory()
        val (take, _) = finishedTake(id)
        take.markLibraryEnd(after("Fünfter Satz."))
        save(take)
        assertNotNull(runBlocking { library.get(id)!! }.finishedAt)
    }

    @Test
    fun aReadTextStartsAgainFromTheBeginningWhenChosenAgain() {
        val id = addStory()
        runBlocking { library.recordReading(id, end = null, fraction = null) }
        val (take, _) = finishedTake(id)
        val reading = take.state.value.library!!
        assertTrue(reading.again)
        assertNull(reading.start)
        take.markLibraryEnd(after("Erster Satz."))
        save(take)
        val text = runBlocking { library.get(id)!! }
        assertNull("wieder unter „Zu lesen“", text.finishedAt)
        assertEquals(after("Erster Satz."), text.progress)
    }

    @Test
    fun discardingTheTakeLeavesTheLibraryUntouched() {
        val id = addStory()
        val (take, _) = finishedTake(id)
        take.markLibraryEnd(after("Zweiter Satz."))
        take.discardDraft()
        val text = runBlocking { library.get(id)!! }
        assertNull(text.progress)
        assertNull(text.finishedAt)
        assertTrue("Dateien der Bibliothek bleiben", library.attachmentOf(text)!!.files.all { it.isFile })
    }

    @Test
    fun theMarkedPositionSurvivesAndroidEndingTheApp() {
        val id = addStory()
        val (take, handle) = finishedTake(id)
        val mark = after("Dritter Satz.")
        take.markLibraryEnd(mark)
        shadowOf(Looper.getMainLooper()).idle()

        val restored = RecordViewModel(app, container, handle)
        waitFor { restored.state.value.library != null && !restored.state.value.attachmentBusy }
        assertEquals(id, restored.state.value.library!!.textId)
        assertEquals(mark, restored.state.value.library!!.end)
    }

    @Test
    fun pickingATextReplacesTheOwnDraftButNeverDeletesLibraryFiles() {
        val id = addStory()
        val photo = container.files.newDraftFile("jpg").apply { writeBytes(ByteArray(10)) }
        val handle = SavedStateHandle()
        handle[RecordViewModel.KEY_PHOTO] = photo.absolutePath
        val withPhoto = RecordViewModel(app, container, handle)
        assertTrue(withPhoto.state.value.attachment is TextAttachment.Photo)

        withPhoto.selectLibraryText(id)
        waitFor { withPhoto.state.value.library != null && !withPhoto.state.value.attachmentBusy }
        assertNull(withPhoto.state.value.attachment)
        assertFalse("eigener Entwurf ist weg", photo.exists())

        withPhoto.removeLibraryText()
        assertNull(withPhoto.state.value.library)
        val text = runBlocking { library.get(id)!! }
        assertTrue(library.attachmentOf(text)!!.files.all { it.isFile })
    }

    @Test
    fun photoPagesKeepOnlyTheReadPagesWithStartAndEndLines() {
        val pages = (1..3).map { n -> container.files.newDraftFile("jpg").apply { writeBytes(byteArrayOf(n.toByte())) } }
        val id = runBlocking { library.add(TextAttachment.Photo(pages), "Buch", move = true) }
        val names = runBlocking { library.get(id)!! }.photoPages
        runBlocking { library.recordReading(id, ReadingPosition.OnPage(1, 0.5f, names[1]), 0.5f) }

        val (take, _) = finishedTake(id)
        assertEquals(ReadingPosition.OnPage(1, 0.5f, names[1]), take.state.value.library!!.start)
        take.markLibraryEnd(ReadingPosition.OnPage(2, 0.3f, names[2]))
        val recordingId = save(take)

        val recording = runBlocking { container.repository.observe(recordingId).first()!! }
        val attachment = container.repository.attachmentOf(recording) as TextAttachment.Photo
        assertEquals("Seiten 2 und 3", listOf<Byte>(2, 3), attachment.pages.map { it.readBytes().single() })
        assertEquals(0.5f, attachment.startY)
        assertEquals(0.3f, attachment.endY)
        val text = runBlocking { library.get(id)!! }
        assertEquals(ReadingPosition.OnPage(2, 0.3f, names[2]), text.progress)
    }

    @Test
    fun editingKeepsTheProgressAtTheSameSentenceOrStartsOverWhenItIsGone() = runBlocking {
        val id = addStory()
        library.recordReading(id, after("Zweiter Satz."), 0.3f)

        // Ein Satz kommt davor dazu: Die Stelle wandert mit.
        val longer = "Neuer Anfang. $story"
        val lost = library.update(
            id,
            "Geschichte",
            emptyList(),
            AttachmentChange.Replace(TextAttachment.Document(container.files.newDraftFile("txt").apply { writeText(longer) }, "Geschichte")),
        )
        assertFalse(lost)
        val moved = library.get(id)!!.progress as ReadingPosition.InText
        assertEquals(longer.indexOf("Zweiter Satz.") + "Zweiter Satz.".length, moved.offset)

        // Der Satz verschwindet: von vorne, mit Hinweis.
        val gone = library.update(
            id,
            "Geschichte",
            emptyList(),
            AttachmentChange.Replace(TextAttachment.Document(container.files.newDraftFile("txt").apply { writeText("Ganz anders.") }, "Geschichte")),
        )
        assertTrue(gone)
        assertNull(library.get(id)!!.progress)
    }

    @Test
    fun searchFindsTitleTagsAndTextIgnoringCase() = runBlocking {
        val id = addStory()
        library.updateDetails(id, "Märchen", listOf("Prosa"), pinned = false)
        suspend fun matches(query: String) = library.observeMatches(query).first()
        assertEquals(listOf(id), matches("MÄRCHEN"))
        assertEquals(listOf(id), matches("prosa"))
        assertEquals(listOf(id), matches("fünfter satz"))
        assertEquals(emptyList<Long>(), matches("nordwind"))
        assertEquals(DocumentKind.PlainText, (library.attachmentOf(library.get(id)!!) as TextAttachment.Document).kind)
    }
}
