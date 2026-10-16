package de.emilybutterfly.stimmtraining.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/** Ablageorte für Audiodateien, Fotos, Dokumente und noch nicht gespeicherte Entwürfe. */
class FileStore(private val context: Context) {
    val audioDir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    val photoDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    val documentDir: File get() = File(context.filesDir, "documents").apply { mkdirs() }
    /** Nicht im Cache: Android darf den Cache leeren, bevor eine Aufnahme gespeichert ist. */
    private val draftDir: File get() = File(context.filesDir, "drafts").apply { mkdirs() }

    fun audio(name: String) = File(audioDir, name)
    fun photo(name: String) = File(photoDir, name)
    fun document(name: String) = File(documentDir, name)

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
        listOf(audioDir, photoDir, documentDir).sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }

    /** Freier Speicher im internen Speicher der App. */
    fun freeBytes(): Long = context.filesDir.usableSpace

    /**
     * Bereitet ein Dokument zum Öffnen in einer anderen App vor: Kopie unter seinem
     * ursprünglichen Namen, damit die andere App ihn anzeigt.
     */
    fun viewIntent(file: File, displayName: String, mimeType: String): Intent {
        val dir = File(context.cacheDir, "open").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, safeFileName(displayName, file.extension))
        file.copyTo(target, overwrite = true)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(target), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        /** Unter diese Grenze soll der freie Speicher nicht fallen: Datenbank und Android brauchen auch Platz. */
        const val RESERVE_BYTES = 10L * 1024 * 1024

        /** Ab hier warnt die App vor dem Aufnehmen. */
        const val LOW_STORAGE_BYTES = 50L * 1024 * 1024

        /** AAC mit 128 kbit/s: knapp 1 MB pro Minute. */
        const val AUDIO_BYTES_PER_MINUTE = 128_000L / 8 * 60

        /** Verschiebt [source] nach [target]; kopiert, wenn Umbenennen nicht geht. */
        fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) return
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            if (!source.delete()) source.deleteOnExit()
            if (!target.isFile) throw IOException("Datei konnte nicht gespeichert werden: ${target.name}")
        }

        /** Ein für jedes Dateisystem gültiger Dateiname mit der Endung [extension]. */
        fun safeFileName(name: String, extension: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trim('.')
            val base = cleaned.ifBlank { "Text" }.take(120)
            return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
        }
    }
}
