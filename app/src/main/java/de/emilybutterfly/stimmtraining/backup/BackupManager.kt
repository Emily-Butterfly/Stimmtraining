package de.emilybutterfly.stimmtraining.backup

import android.content.Context
import android.net.Uri
import de.emilybutterfly.stimmtraining.BuildConfig
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.RecordingDao
import de.emilybutterfly.stimmtraining.data.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportResult(val imported: Int, val skipped: Int, val incomplete: Int)

/** Schreibt und liest Sicherungen aller Aufnahmen als ZIP-Datei (siehe [BackupFormat]). */
class BackupManager(
    private val context: Context,
    private val dao: RecordingDao,
    private val files: FileStore,
) {
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Die Zieldatei konnte nicht geöffnet werden.")
        output.use { export(it) }
    }

    /** Schreibt die Sicherung nach [output] (ohne es zu schließen) und liefert die Anzahl Aufnahmen. */
    suspend fun export(output: OutputStream): Int = withContext(Dispatchers.IO) {
        val recordings = dao.getAll()
        val zip = ZipOutputStream(BufferedOutputStream(output))
        // Audio (AAC) und Fotos (JPEG) sind bereits komprimiert.
        zip.setLevel(Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
        val manifest = BackupFormat.manifest(recordings, System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (recording in recordings) {
            addFile(zip, BackupFormat.AUDIO_DIR + recording.audioFile, files.audio(recording.audioFile))
            recording.photoFile?.let { addFile(zip, BackupFormat.PHOTO_DIR + it, files.photo(it)) }
        }
        zip.finish()
        zip.flush()
        recordings.size
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Die Datei konnte nicht geöffnet werden.")
        input.use { import(it) }
    }

    /**
     * Übernimmt alle Aufnahmen aus der Sicherung, die es auf diesem Gerät noch nicht gibt.
     * Vorhandene Aufnahmen werden nie überschrieben oder gelöscht.
     */
    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "import-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            var manifest: String? = null
            val zip = ZipInputStream(BufferedInputStream(input))
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = BackupFormat.safeEntryName(entry.name)
                if (!entry.isDirectory && name != null) {
                    if (name == BackupFormat.MANIFEST) {
                        manifest = zip.readBytes().toString(Charsets.UTF_8)
                    } else {
                        val target = File(staging, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
            }

            val entries = BackupFormat.parse(
                manifest ?: throw BackupException("Diese Datei ist keine Stimmtraining-Sicherung (manifest.json fehlt)."),
            )
            val known = dao.allUuids().toMutableSet()
            var imported = 0
            var skipped = 0
            var incomplete = 0
            for (entry in entries) {
                val recording = entry.recording
                if (!known.add(recording.uuid)) {
                    skipped++
                    continue
                }
                val audio = File(staging, BackupFormat.AUDIO_DIR + recording.audioFile)
                if (!audio.isFile) {
                    incomplete++
                    continue
                }
                val extension = audio.extension.lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "m4a"
                val audioName = "${recording.uuid}.$extension"
                FileStore.move(audio, files.audio(audioName))
                val photo = recording.photoFile
                    ?.let { File(staging, BackupFormat.PHOTO_DIR + it) }
                    ?.takeIf { it.isFile }
                val photoName = photo?.let { source ->
                    RecordingRepository.newPhotoName(recording.uuid).also { FileStore.move(source, files.photo(it)) }
                }
                dao.insert(recording.copy(id = 0, audioFile = audioName, photoFile = photoName))
                imported++
            }
            ImportResult(imported, skipped, incomplete)
        } finally {
            staging.deleteRecursively()
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("m4a", "mp4", "aac", "3gp", "ogg", "opus", "mp3", "wav")
    }
}
