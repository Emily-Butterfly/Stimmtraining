package de.emilybutterfly.stimmtraining.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.ImageImporter
import de.emilybutterfly.stimmtraining.data.PastedText
import de.emilybutterfly.stimmtraining.data.RecordingParams
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.document.DocumentImporter
import de.emilybutterfly.stimmtraining.data.document.DocumentReadException
import de.emilybutterfly.stimmtraining.data.document.UnsupportedDocumentException
import de.emilybutterfly.stimmtraining.ui.components.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

fun CreationExtras.app(): StimmtrainingApp =
    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as StimmtrainingApp

/** Legt Formularwerte so ab, dass sie das Beenden der App durch Android überstehen. */
fun SavedStateHandle.putParams(prefix: String, params: RecordingParams) {
    this["$prefix.title"] = params.title
    this["$prefix.exercise"] = params.exercise
    this["$prefix.warmedUp"] = params.warmedUp
    this["$prefix.warmUpMinutes"] = params.warmUpMinutes
    this["$prefix.voiceCondition"] = params.voiceCondition
    this["$prefix.selfRating"] = params.selfRating
    this["$prefix.notes"] = params.notes
}

fun SavedStateHandle.getParams(prefix: String) = RecordingParams(
    title = get<String>("$prefix.title") ?: "",
    exercise = get<String>("$prefix.exercise") ?: "",
    warmedUp = get<Boolean>("$prefix.warmedUp") ?: false,
    warmUpMinutes = get<String>("$prefix.warmUpMinutes") ?: "",
    voiceCondition = get<Int>("$prefix.voiceCondition"),
    selfRating = get<Int>("$prefix.selfRating"),
    notes = get<String>("$prefix.notes") ?: "",
)

/** Bereitet die Zieldatei für ein Kamerafoto vor und liefert die URI für die Kamera-App. */
fun FileStore.prepareCameraCapture(): Uri {
    cameraCaptureFile.delete()
    return uriFor(cameraCaptureFile)
}

/**
 * Legt einen Entwurf an und räumt ihn wieder weg, falls der Aufrufer inzwischen abgebrochen
 * wurde (z. B. weil die Ansicht während eines langen Imports geschlossen wurde). Sonst bliebe
 * die Datei liegen, ohne dass etwas auf sie verweist. [create] bekommt eine Abfrage, ob
 * abgebrochen wurde, damit lange Kopien früh aufhören können.
 */
internal suspend fun <T> createDraft(file: (T) -> File, create: (isCancelled: () -> Boolean) -> T): T {
    val job = currentCoroutineContext().job
    var created: T? = null
    try {
        return withContext(Dispatchers.IO) { create { job.isCancelled }.also { created = it } }
    } catch (e: CancellationException) {
        // withContext verwirft das Ergebnis, wenn der Aufrufer inzwischen abgebrochen wurde.
        created?.let { file(it).delete() }
        throw e
    }
}

/** Übernimmt ein Foto als neuen Entwurf (JPEG, gedreht und verkleinert). */
suspend fun FileStore.importDraftPhoto(context: Context, source: Uri): File = createDraft({ it }) {
    val target = newDraftFile("jpg")
    try {
        ImageImporter.import(context.contentResolver, source, target)
        target
    } catch (e: Exception) {
        target.delete()
        throw e
    }
}

/**
 * Übernimmt mehrere Bilder aus der Galerie als Entwürfe, in der gewählten Reihenfolge. Bilder,
 * die sich nicht lesen lassen, fehlen im Ergebnis und werden mit [onSkipped] gemeldet; ließ sich
 * gar keins lesen, kommt der Fehler.
 */
suspend fun FileStore.importDraftPhotos(context: Context, sources: List<Uri>, onSkipped: (Int) -> Unit = {}): List<File> {
    val imported = mutableListOf<File>()
    var failure: Exception? = null
    try {
        for (source in sources) {
            try {
                imported += importDraftPhoto(context, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
    } catch (e: CancellationException) {
        imported.forEach { it.delete() }
        throw e
    }
    if (imported.isEmpty()) failure?.let { throw it }
    if (imported.size < sources.size) onSkipped(sources.size - imported.size)
    return imported
}

/** Meldung, wenn einzelne Bilder (z. B. HEIC auf älteren Handys) nicht übernommen werden konnten. */
fun skippedPhotosMessage(count: Int): String =
    if (count == 1) "1 Bild ließ sich nicht übernehmen." else "$count Bilder ließen sich nicht übernehmen."

/** Übernimmt das zuletzt mit der Kamera aufgenommene Foto als Entwurf. */
suspend fun FileStore.importCameraCapture(context: Context): File {
    val capture = cameraCaptureFile
    return try {
        importDraftPhoto(context, Uri.fromFile(capture))
    } finally {
        capture.delete()
    }
}

/** Übernimmt eine Datei (PDF, Word, Text, …) als Entwurf. */
suspend fun FileStore.importDraftDocument(context: Context, source: Uri): TextAttachment.Document =
    createDraft({ it.file }) { isCancelled ->
        DocumentImporter.import(context.contentResolver, this, source, isCancelled = isCancelled)
    }

/** Übernimmt eingefügten Text als Entwurf; ohne [name] heißt er wie seine erste Zeile. */
suspend fun FileStore.createDraftText(text: String, name: String?): TextAttachment.Document = createDraft({ it.file }) {
    TextAttachment.Document(PastedText.createDraft(this, text), name ?: PastedText.name(text))
}

/**
 * Meldung, wenn ein Text-Anhang nicht übernommen werden konnte. Eigene Meldungen der App sind
 * deutsch und verständlich; technische Systemmeldungen (oft englisch) werden nicht gezeigt.
 */
fun importFailureMessage(isPhoto: Boolean, e: Throwable): String {
    val own = generateSequence(e) { it.cause }.firstOrNull { it is UnsupportedDocumentException || it is DocumentReadException }
    return when {
        own?.message != null -> own.message!!
        e is FileNotFoundException -> "Die Datei konnte nicht geöffnet werden."
        isPhoto -> "Das Foto konnte nicht übernommen werden."
        else -> "Die Datei konnte nicht übernommen werden."
    }
}

/** Ein „Öffnen mit …“-Dialog für ein Dokument. */
suspend fun FileStore.openWithChooser(document: TextAttachment.Document): Intent = withContext(Dispatchers.IO) {
    Intent.createChooser(viewIntent(document.file, document.name, document.kind.mimeType), "Öffnen mit")
}

/**
 * Ändert die Seiten eines Foto-Entwurfs. Was dabei aus dem Entwurf herausfällt (gelöschte oder
 * durch eine gedrehte Fassung ersetzte Seiten), wird gelöscht. Geänderte Seiten verlieren die
 * Abschnittslinien, weil sie nicht mehr zum Bild passen würden.
 */
internal object DraftPages {
    fun move(photo: TextAttachment.Photo, from: Int, to: Int): TextAttachment.Photo {
        if (from !in photo.pages.indices || to !in photo.pages.indices || from == to) return photo
        val pages = photo.pages.toMutableList()
        pages.add(to, pages.removeAt(from))
        return photo.withPages(pages)
    }

    /** null, wenn die letzte Seite gelöscht wurde. */
    fun delete(photo: TextAttachment.Photo, index: Int): TextAttachment.Photo? {
        val page = photo.pages.getOrNull(index) ?: return photo
        page.delete()
        val rest = photo.pages - page
        return if (rest.isEmpty()) null else photo.withPages(rest)
    }

    /**
     * Dreht Seite [index] in einen neuen Entwurf. Neuer Dateiname, damit kein altes Bild aus dem
     * Cache erscheint; die alte Entwurfsdatei wird erst danach gelöscht.
     */
    suspend fun rotate(files: FileStore, photo: TextAttachment.Photo, index: Int, clockwise: Boolean): TextAttachment.Photo {
        val page = photo.pages.getOrNull(index) ?: return photo
        files.requireSpaceFor(page.length() * 2)
        val rotated = createDraft({ it }) {
            val target = files.newDraftFile("jpg")
            try {
                ImageImporter.rotate(page, target, clockwise)
                target
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
        page.delete()
        return photo.withPages(photo.pages.toMutableList().also { it[index] = rotated })
    }

    /** Hängt [added] an [photo] an (oder beginnt damit); was über [TextAttachment.MAX_PAGES] hinausgeht, wird gelöscht. */
    fun append(photo: TextAttachment.Photo?, added: List<File>): Pair<TextAttachment.Photo, Int> {
        val pages = photo?.pages.orEmpty() + added
        val kept = pages.take(TextAttachment.MAX_PAGES)
        val dropped = pages.drop(TextAttachment.MAX_PAGES)
        dropped.forEach { it.delete() }
        return (photo?.withPages(kept) ?: TextAttachment.Photo(kept)) to dropped.size
    }

    /**
     * Kopiert gespeicherte Seiten in Entwürfe, damit sich die Seiten bearbeiten lassen, ohne das
     * Original anzufassen – bis zum Speichern lässt sich alles verwerfen.
     */
    suspend fun copyToDrafts(files: FileStore, photo: TextAttachment.Photo): TextAttachment.Photo {
        files.requireSpaceFor(photo.pages.sumOf { it.length() })
        val copies = mutableListOf<File>()
        try {
            withContext(Dispatchers.IO) {
                for (page in photo.pages) {
                    val copy = files.newDraftFile("jpg")
                    copies += copy
                    page.copyTo(copy, overwrite = true)
                }
            }
            return photo.copy(pages = copies.toList())
        } catch (e: Throwable) {
            // Auch beim Abbrechen: Halbe Kopien sollen nicht liegen bleiben.
            copies.forEach { it.delete() }
            throw e
        }
    }
}

/** Bricht mit verständlicher Meldung ab, wenn für [bytes] kein Platz mehr ist (die Reserve bleibt frei). */
fun FileStore.requireSpaceFor(bytes: Long) {
    val space = freeBytes() - FileStore.RESERVE_BYTES
    if (bytes > space) {
        throw DocumentReadException("Nicht genug Speicherplatz (nutzbar sind noch ${formatBytes(space.coerceAtLeast(0))}).")
    }
}

/** Meldung, wenn eine Seite sich nicht drehen ließ. */
fun pageFailureMessage(e: Throwable): String =
    (e as? DocumentReadException)?.message ?: "Die Seite ließ sich nicht drehen."
