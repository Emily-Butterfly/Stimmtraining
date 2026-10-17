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
