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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

/** Übernimmt ein Foto als neuen Entwurf (JPEG, gedreht und verkleinert). */
suspend fun FileStore.importDraftPhoto(context: Context, source: Uri): File = withContext(Dispatchers.IO) {
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
    withContext(Dispatchers.IO) { DocumentImporter.import(context.contentResolver, this@importDraftDocument, source) }

/** Übernimmt eingefügten Text als Entwurf; ohne [name] heißt er wie seine erste Zeile. */
suspend fun FileStore.createDraftText(text: String, name: String?): TextAttachment.Document = withContext(Dispatchers.IO) {
    TextAttachment.Document(PastedText.createDraft(this@createDraftText, text), name ?: PastedText.name(text))
}

/** Ein „Öffnen mit …“-Dialog für ein Dokument. */
suspend fun FileStore.openWithChooser(document: TextAttachment.Document): Intent = withContext(Dispatchers.IO) {
    Intent.createChooser(viewIntent(document.file, document.name, document.kind.mimeType), "Öffnen mit")
}
