package de.emilybutterfly.stimmtraining.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/** Ablageorte für Audiodateien, Fotos und noch nicht gespeicherte Entwürfe. */
class FileStore(private val context: Context) {
    val audioDir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    val photoDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    /** Nicht im Cache: Android darf den Cache leeren, bevor eine Aufnahme gespeichert ist. */
    private val draftDir: File get() = File(context.filesDir, "drafts").apply { mkdirs() }

    fun audio(name: String) = File(audioDir, name)
    fun photo(name: String) = File(photoDir, name)

    fun newDraftFile(extension: String) = File(draftDir, "draft_${UUID.randomUUID()}.$extension")

    /**
     * Fester Pfad für Kamerafotos. Fest, damit das Ergebnis auch dann noch gefunden wird,
     * wenn Android die App beendet, während die Kamera-App offen ist.
     */
    val cameraCaptureFile: File get() = File(draftDir, "camera_capture.jpg")

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Löscht Entwürfe, die älter als einen Tag sind (z. B. nach einem Absturz). */
    fun cleanOldDrafts() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        draftDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    fun totalBytes(): Long =
        listOf(audioDir, photoDir).sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }

    companion object {
        /** Verschiebt [source] nach [target]; kopiert, wenn Umbenennen nicht geht. */
        fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) return
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            if (!source.delete()) source.deleteOnExit()
            if (!target.isFile) throw IOException("Datei konnte nicht gespeichert werden: ${target.name}")
        }
    }
}
