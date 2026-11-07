package de.emilybutterfly.stimmtraining.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/** Ablageorte für Audiodateien, Fotos, Dokumente, die Bibliothek und noch nicht gespeicherte Entwürfe. */
class FileStore(private val context: Context) {
    val audioDir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    val photoDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    val documentDir: File get() = File(context.filesDir, "documents").apply { mkdirs() }
    /** Fotos und Dateien der Bibliothek. */
    val libraryDir: File get() = File(context.filesDir, "library").apply { mkdirs() }
    /** Nicht im Cache: Android darf den Cache leeren, bevor eine Aufnahme gespeichert ist. */
    private val draftDir: File get() = File(context.filesDir, "drafts").apply { mkdirs() }

    /** Hier wird eine Sicherung beim Einlesen ausgepackt. */
    val importStagingDir: File get() = File(context.filesDir, "import-staging")

    fun audio(name: String) = File(audioDir, name)
    fun photo(name: String) = File(photoDir, name)
    fun document(name: String) = File(documentDir, name)
    fun library(name: String) = File(libraryDir, name)

    fun newDraftFile(extension: String) = File(draftDir, "draft_${UUID.randomUUID()}.$extension")

    /**
     * Ein gesicherter Pfad, aber nur, wenn er auf einen vorhandenen Entwurf der App zeigt. Was
     * beim Wiederherstellen ankommt, darf nie auf andere Dateien (z. B. die Datenbank) zeigen –
     * die würden sonst beim Verwerfen gelöscht oder beim Speichern verschoben.
     */
    fun ownDraft(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        val inside = runCatching { file.canonicalFile.parentFile == draftDir.canonicalFile }.getOrDefault(false)
        return file.takeIf { inside && it.isFile }
    }

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
        listOf(audioDir, photoDir, documentDir, libraryDir).sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }

    /** Freier Speicher im internen Speicher der App. */
    fun freeBytes(): Long = context.filesDir.usableSpace

    /**
     * Bereitet ein Dokument zum Öffnen in einer anderen App vor: Kopie unter seinem
     * ursprünglichen Namen, damit die andere App ihn anzeigt.
     */
    fun viewIntent(file: File, displayName: String, mimeType: String): Intent {
        val target = handoffCopy(OPEN_DIR, file, displayName)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(target), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Eine Kopie von [file] für eine andere App (Öffnen, Teilen), unter [displayName]. Jede Kopie
     * bekommt einen eigenen Unterordner und damit eine eigene Adresse: Eine App, die eine frühere
     * Kopie noch liest (z. B. beim Hochladen), verliert sie nicht, und keine bekommt versehentlich
     * eine andere Datei unter derselben Adresse. Kopien älter als [HANDOFF_KEEP_MS] werden aufgeräumt.
     */
    fun handoffCopy(folder: String, file: File, displayName: String): File {
        val root = File(context.cacheDir, folder)
        val cutoff = System.currentTimeMillis() - HANDOFF_KEEP_MS
        root.listFiles()?.forEach { if (it.isFile || it.lastModified() < cutoff) it.deleteRecursively() }
        if (file.length() > context.cacheDir.usableSpace - RESERVE_BYTES) {
            throw IOException("Zu wenig Speicherplatz, um die Datei an eine andere App zu geben.")
        }
        val dir = File(root, UUID.randomUUID().toString())
        val target = File(dir, safeFileName(displayName, file.extension))
        try {
            if (!dir.mkdirs()) throw IOException("Ordner für die Kopie fehlt.")
            file.copyTo(target, overwrite = true)
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw IOException("Die Datei ließ sich nicht für die andere App vorbereiten.", e)
        }
        return target
    }

    companion object {
        const val OPEN_DIR = "open"
        const val SHARE_DIR = "shared"

        /** So lange bleiben Kopien für andere Apps liegen (siehe [handoffCopy]). */
        const val HANDOFF_KEEP_MS = 24L * 60 * 60 * 1000

        /** Der Dateityp einer Aufnahme für andere Apps, nach der Endung. */
        fun audioMimeType(extension: String): String = when (extension.lowercase()) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "3gp", "3gpp" -> "audio/3gpp"
            "ogg", "oga", "opus" -> "audio/ogg"
            "webm" -> "audio/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "amr" -> "audio/amr"
            else -> "audio/*"
        }

        /** Unter diese Grenze soll der freie Speicher nicht fallen: Datenbank und Android brauchen auch Platz. */
        const val RESERVE_BYTES = 10L * 1024 * 1024

        /** Ab hier warnt die App vor dem Aufnehmen. */
        const val LOW_STORAGE_BYTES = 50L * 1024 * 1024

        /** Platz für die Endung bleibt bis zur Grenze von 255 Bytes. */
        private const val MAX_NAME_BYTES = 200

        /** AAC mit 128 kbit/s: knapp 1 MB pro Minute. */
        const val AUDIO_BYTES_PER_MINUTE = 128_000L / 8 * 60

        /** Verschiebt [source] nach [target]; kopiert, wenn Umbenennen nicht geht. */
        fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) return
            try {
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                // Keine halbe Kopie liegen lassen; das Original ist noch da.
                target.delete()
                throw e
            }
            if (!source.delete()) source.deleteOnExit()
            if (!target.isFile) throw IOException("Datei konnte nicht gespeichert werden: ${target.name}")
        }

        /** Ein für jedes Dateisystem gültiger Dateiname mit der Endung [extension]. */
        fun safeFileName(name: String, extension: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trim('.')
            // Dateisysteme begrenzen Namen auf 255 Bytes, nicht Zeichen: Umlaute, Emojis und
            // chinesische Schrift brauchen mehrere Bytes je Zeichen.
            var base = cleaned.ifBlank { "Text" }.takeWhole(120)
            while (base.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) base = base.takeWhole(base.length - 1)
            return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
        }
    }
}
