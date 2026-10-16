package de.emilybutterfly.stimmtraining.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import de.emilybutterfly.stimmtraining.StimmtrainingApp
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.ImageImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun CreationExtras.app(): StimmtrainingApp =
    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as StimmtrainingApp

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
