package de.emilybutterfly.stimmtraining.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.emilybutterfly.stimmtraining.BuildConfig
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.RecordingDao
import de.emilybutterfly.stimmtraining.data.RecordingRepository
import de.emilybutterfly.stimmtraining.data.joinPageNames
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** [libraryImported]: Texte, die neu in die Bibliothek kamen. */
data class ImportResult(val imported: Int, val skipped: Int, val incomplete: Int, val libraryImported: Int = 0)

/** Was in eine Sicherung kam. */
data class ExportResult(val recordings: Int, val texts: Int)

/** Schreibt und liest Sicherungen aller Aufnahmen und der Bibliothek als ZIP-Datei (siehe [BackupFormat]). */
class BackupManager(
    private val context: Context,
    private val dao: RecordingDao,
    private val library: LibraryRepository,
    private val files: FileStore,
) {
    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            openForWriting(context.contentResolver, uri).use { export(it) }
        } catch (e: Exception) {
            // Keine halbe Sicherung unter einem gültigen Namen zurücklassen.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    /** Schreibt die Sicherung nach [output] (ohne es zu schließen). */
    suspend fun export(output: OutputStream): ExportResult = withContext(Dispatchers.IO) {
        val recordings = dao.getAll()
        val texts = library.all()
        val zip = ZipOutputStream(BufferedOutputStream(output))
        // Audio (AAC), Fotos (JPEG) und die meisten Dokumente sind bereits komprimiert.
        zip.setLevel(Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
        val manifest = BackupFormat.manifest(recordings, System.currentTimeMillis(), BuildConfig.VERSION_NAME, texts)
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (recording in recordings) {
            addFile(zip, BackupFormat.AUDIO_DIR + recording.audioFile, files.audio(recording.audioFile))
            recording.photoPages.forEach { addFile(zip, BackupFormat.PHOTO_DIR + it, files.photo(it)) }
            recording.documentFile?.let { addFile(zip, BackupFormat.DOCUMENT_DIR + it, files.document(it)) }
        }
        for (text in texts) {
            (text.photoPages + listOfNotNull(text.documentFile)).forEach { addFile(zip, BackupFormat.LIBRARY_DIR + it, files.library(it)) }
        }
        zip.finish()
        zip.flush()
        ExportResult(recordings.size, texts.size)
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

            val json = manifest ?: throw BackupException("Diese Datei ist keine Stimmtraining-Sicherung (manifest.json fehlt).")
            val entries = BackupFormat.parse(json)
            val libraryEntries = BackupFormat.parseLibrary(json)
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
                val photos = recording.photoPages
                    .map { File(staging, BackupFormat.PHOTO_DIR + it) }
                    .filter { it.isFile }
                val photoNames = photos.mapIndexed { page, source ->
                    RecordingRepository.newPhotoName(recording.uuid, page).also { FileStore.move(source, files.photo(it)) }
                }
                // Fehlt eine Seite, passen die Abschnittslinien nicht mehr sicher.
                val complete = photoNames.size == recording.photoPages.size
                val document = recording.documentFile
                    ?.let { File(staging, BackupFormat.DOCUMENT_DIR + it) }
                    ?.takeIf { it.isFile }
                val documentName = document?.let { source ->
                    RecordingRepository.newDocumentName(recording.uuid, source.extension.lowercase())
                        .also { FileStore.move(source, files.document(it)) }
                }
                dao.insert(
                    recording.copy(
                        id = 0,
                        audioFile = audioName,
                        photoFile = photoNames.joinPageNames(),
                        sectionStartY = recording.sectionStartY.takeIf { complete },
                        sectionEndY = recording.sectionEndY.takeIf { complete },
                        documentFile = documentName,
                        documentName = recording.documentName.takeIf { documentName != null },
                    ),
                )
                imported++
            }
            ImportResult(imported, skipped, incomplete, importLibrary(libraryEntries, staging))
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Übernimmt die Texte der Bibliothek, die es hier noch nicht gibt; Texte ohne ihre Dateien entfallen. */
    private suspend fun importLibrary(entries: List<BackupFormat.LibraryEntry>, staging: File): Int {
        val known = library.knownUuids().toMutableSet()
        var imported = 0
        for (entry in entries) {
            val text = entry.text
            if (!known.add(text.uuid)) continue
            val stamp = System.currentTimeMillis()
            val names = mutableMapOf<String, String>()
            val moved = mutableListOf<File>()
            try {
                text.photoPages.forEachIndexed { index, page ->
                    val source = File(staging, BackupFormat.LIBRARY_DIR + page)
                    if (source.isFile) {
                        val name = "${text.uuid}_${stamp}_${index + 1}.jpg"
                        FileStore.move(source, files.library(name))
                        moved += files.library(name)
                        names[page] = name
                    }
                }
                val document = text.documentFile?.let { File(staging, BackupFormat.LIBRARY_DIR + it) }?.takeIf { it.isFile }
                val documentName = document?.let { source ->
                    "${text.uuid}_$stamp.${source.extension.lowercase()}".also {
                        FileStore.move(source, files.library(it))
                        moved += files.library(it)
                    }
                }
                if (names.isEmpty() && documentName == null) continue
                val pages = text.photoPages.mapNotNull { names[it] }
                val progress = text.progress
                // Die Stelle zeigt auf eine Seite; fehlt die, beginnt der Text von vorne.
                val kept = when (progress) {
                    is ReadingPosition.OnPage -> if (pages.size == text.photoPages.size || documentName != null) {
                        progress.copy(pageName = progress.pageName?.let { names[it] })
                    } else {
                        null
                    }
                    else -> progress
                }
                library.insertImported(
                    text.copy(photoFiles = pages.joinPageNames(), documentFile = documentName)
                        .withProgress(kept, text.progressFraction),
                )
                imported++
            } catch (e: Exception) {
                moved.forEach { it.delete() }
                throw e
            }
        }
        return imported
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("m4a", "mp4", "aac", "3gp", "ogg", "opus", "mp3", "wav")

        /**
         * Öffnet [uri] zum Schreiben. "wt" kürzt eine überschriebene Datei; ohne das bliebe
         * bei manchen Anbietern das Ende der alten, größeren Sicherung stehen.
         */
        fun openForWriting(resolver: ContentResolver, uri: Uri) =
            (
                try {
                    resolver.openOutputStream(uri, "wt")
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: UnsupportedOperationException) {
                    null
                } catch (_: FileNotFoundException) {
                    null
                }
                ) ?: resolver.openOutputStream(uri, "w")
                ?: throw IOException("Die Zieldatei konnte nicht geöffnet werden.")
    }
}
