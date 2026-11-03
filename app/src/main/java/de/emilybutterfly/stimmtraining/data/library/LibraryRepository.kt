package de.emilybutterfly.stimmtraining.data.library

import android.graphics.Bitmap
import de.emilybutterfly.stimmtraining.data.AttachmentChange
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import de.emilybutterfly.stimmtraining.data.document.DocumentText
import de.emilybutterfly.stimmtraining.data.document.PdfPages
import de.emilybutterfly.stimmtraining.data.joinPageNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Herkunft einer Fotoseite beim Bearbeiten: der Dateiname der Seite in der Bibliothek und ob sie
 * gedreht wurde. Damit bleibt der Lesefortschritt auf seiner Seite, wenn Seiten umsortiert werden.
 */
data class PageOrigin(val name: String, val rotated: Boolean = false)

/** Texte der Bibliothek: ablegen, ordnen, suchen und den Lesefortschritt festhalten. */
class LibraryRepository(
    private val dao: LibraryDao,
    private val files: FileStore,
) {
    fun observeAll(): Flow<List<LibraryText>> = dao.observeAll()

    fun observe(id: Long): Flow<LibraryText?> = dao.observe(id)

    suspend fun get(id: Long): LibraryText? = dao.get(id)

    // --- für Sicherungen ---

    suspend fun all(): List<LibraryText> = dao.getAll()

    suspend fun knownUuids(): Set<String> = dao.allUuids().toSet()

    suspend fun count(): Int = dao.count()

    /** Ändert sich bei jedem Hinzufügen, Bearbeiten oder Löschen eines Texts. */
    suspend fun fingerprint(): String = dao.fingerprint()

    /** Nimmt einen Text aus einer Sicherung auf; seine Dateien liegen schon in der Bibliothek. */
    suspend fun insertImported(text: LibraryText): Long = withContext(Dispatchers.IO) {
        dao.insertWithSearch(text.copy(id = 0), searchContent(text))
    }

    /** IDs der Texte, in deren Titel, Schlagworten oder Inhalt [query] vorkommt (ohne Groß-/Kleinschreibung). */
    fun observeMatches(query: String): Flow<List<Long>> = dao.observeMatches(normalize(query))

    /** Die Dateien eines Texts; null, wenn sie fehlen (z. B. nach einem unvollständigen Backup). */
    fun attachmentOf(text: LibraryText): TextAttachment? = when {
        text.documentFile != null -> TextAttachment.Document(files.library(text.documentFile), text.documentName ?: text.documentFile)
        text.photoPages.isNotEmpty() -> TextAttachment.Photo(text.photoPages.map(files::library))
        else -> null
    }

    /**
     * Legt einen neuen Text an. Mit [move] werden Entwürfe übernommen (sie gehören danach der
     * Bibliothek); sonst werden die Dateien kopiert, etwa aus einer gespeicherten Aufnahme.
     */
    suspend fun add(attachment: TextAttachment, title: String, tags: List<String> = emptyList(), move: Boolean): Long =
        withContext(Dispatchers.IO) {
            val uuid = UUID.randomUUID().toString()
            val stored = store(uuid, attachment, move)
            try {
                val now = System.currentTimeMillis()
                val text = LibraryText(
                    uuid = uuid,
                    title = cleanTitle(title),
                    tags = LibraryText.joinTags(tags),
                    photoFiles = stored.photoFiles,
                    documentFile = stored.documentFile,
                    documentName = stored.documentName,
                    createdAt = now,
                    updatedAt = now,
                )
                dao.insertWithSearch(text, searchContent(text))
            } catch (e: Exception) {
                stored.undo()
                throw e
            }
        }

    /** Titel, Schlagworte und angeheftet ändern. */
    suspend fun updateDetails(id: Long, title: String, tags: List<String>, pinned: Boolean) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        val updated = current.copy(
            title = cleanTitle(title),
            tags = LibraryText.joinTags(tags),
            pinned = pinned,
            updatedAt = System.currentTimeMillis(),
        )
        dao.update(updated)
        dao.upsertSearch(LibrarySearch(id, searchContent(updated)))
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        dao.update(current.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Speichert Änderungen aus dem Bearbeiten. Ein neuer Inhalt ([change]) ersetzt die Dateien; der
     * Fortschritt bleibt beim selben Satz bzw. auf derselben Seite ([origins]: woher jede neue
     * Fotoseite stammt). Liefert true, wenn die gelesene Stelle nicht mehr zu finden war und der
     * Text deshalb wieder von vorne beginnt.
     */
    suspend fun update(
        id: Long,
        title: String,
        tags: List<String>,
        change: AttachmentChange,
        origins: List<PageOrigin?> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext false
        val replacement = (change as? AttachmentChange.Replace)?.attachment?.takeIf { it.files.all(File::isFile) }
        if (replacement == null) {
            updateDetails(id, title, tags, current.pinned)
            return@withContext false
        }
        val stored = store(current.uuid, replacement, move = true)
        var lost = false
        val updated = try {
            val withContent = current.copy(
                title = cleanTitle(title),
                tags = LibraryText.joinTags(tags),
                photoFiles = stored.photoFiles,
                documentFile = stored.documentFile,
                documentName = stored.documentName,
                updatedAt = System.currentTimeMillis(),
            )
            val progress = current.progress
            val moved = when (progress) {
                null -> null
                is ReadingPosition.OnPage -> movedPage(progress, origins, stored.photoFiles)
                is ReadingPosition.InText -> attachmentOf(withContent)?.let { relocated(progress, it) }
            }
            lost = progress != null && moved == null
            val result = withContent.withProgress(moved?.first, moved?.second)
            dao.update(result)
            result
        } catch (e: Exception) {
            stored.undo()
            throw e
        }
        dao.upsertSearch(LibrarySearch(id, searchContent(updated)))
        val kept = (stored.photoFiles?.split('\n').orEmpty() + listOfNotNull(stored.documentFile)).toSet()
        (current.photoPages + listOfNotNull(current.documentFile)).filter { it !in kept }.forEach { files.library(it).delete() }
        lost
    }

    /** Setzt die gelesene Stelle (z. B. von Hand korrigiert); null beginnt den Text von vorne. */
    suspend fun setProgress(id: Long, position: ReadingPosition?) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        val fraction = position?.let { fractionOf(current, it) }
        dao.update(current.withProgress(position, fraction).copy(finishedAt = null, updatedAt = System.currentTimeMillis()))
    }

    /** Wie viel bis [position] gelesen ist (0..1), für die Anzeige in der Liste. */
    private fun fractionOf(text: LibraryText, position: ReadingPosition): Float? {
        val attachment = attachmentOf(text) ?: return null
        return when (position) {
            is ReadingPosition.InText -> (attachment as? TextAttachment.Document)?.takeIf { it.kind != DocumentKind.Pdf }
                ?.let { runCatching { DocumentText.read(it.file, it.kind) }.getOrNull() }
                ?.let { TextProgress.fraction(TextLayout(it), position.offset) }
            is ReadingPosition.OnPage -> {
                val pages = when (attachment) {
                    is TextAttachment.Photo -> attachment.pages.size
                    is TextAttachment.Document -> runCatching { PdfPages.open(attachment.file).use { it.pageCount } }.getOrNull()
                } ?: return null
                ((position.page + position.y) / pages).coerceIn(0f, 1f)
            }
        }
    }

    /** „Nochmal lesen“: zurück nach „Zu lesen“, von vorne. */
    suspend fun readAgain(id: Long) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        dao.update(current.withProgress(null, null).copy(finishedAt = null, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Hält fest, was in einer gespeicherten Aufnahme gelesen wurde: bis [end] (dann bleibt der Text
     * unter „Zu lesen“) oder mit [end] = null bis zum Ende (dann wandert er nach „Gelesen“).
     */
    suspend fun recordReading(id: Long, end: ReadingPosition?, fraction: Float?) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        val now = System.currentTimeMillis()
        val updated = if (end == null) {
            current.withProgress(null, null).copy(finishedAt = now)
        } else {
            current.withProgress(end, fraction).copy(finishedAt = null)
        }
        dao.update(updated.copy(lastReadAt = now, updatedAt = now))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        dao.delete(id)
        (current.photoPages + listOfNotNull(current.documentFile)).forEach { files.library(it).delete() }
    }

    /** Wo das Weiterlesen beginnt. [lost]: Die gespeicherte Stelle war nicht mehr zu finden. */
    data class StartPoint(val position: ReadingPosition?, val lost: Boolean = false)

    /**
     * Die Stelle, ab der [text] weitergelesen wird – an den aktuellen Inhalt angepasst (ein Satz
     * kann sich verschoben haben, eine Seite den Platz gewechselt). Zu Ende gelesene Texte beginnen
     * von vorne.
     */
    suspend fun startOf(text: LibraryText): StartPoint = withContext(Dispatchers.IO) {
        val progress = text.progress
        if (text.isFinished || progress == null) return@withContext StartPoint(null)
        val attachment = attachmentOf(text) ?: return@withContext StartPoint(null, lost = true)
        when (progress) {
            is ReadingPosition.InText -> {
                val document = attachment as? TextAttachment.Document
                val content = document?.takeIf { it.kind != DocumentKind.Pdf }
                    ?.let { runCatching { DocumentText.read(it.file, it.kind) }.getOrNull() }
                val resolved = content?.let { TextProgress.resolve(it, progress) }
                if (resolved == null) StartPoint(null, lost = true) else StartPoint(resolved)
            }
            is ReadingPosition.OnPage -> when (attachment) {
                is TextAttachment.Photo -> {
                    val index = progress.pageName?.let { name -> attachment.pages.indexOfFirst { it.name == name } }
                        ?.takeIf { it >= 0 } ?: progress.page.takeIf { progress.pageName == null }
                    if (index == null || index !in attachment.pages.indices) {
                        StartPoint(null, lost = true)
                    } else {
                        StartPoint(progress.copy(page = index, pageName = attachment.pages[index].name))
                    }
                }
                is TextAttachment.Document -> if (attachment.kind == DocumentKind.Pdf) StartPoint(progress) else StartPoint(null, lost = true)
            }
        }
    }

    /**
     * Der in einer Aufnahme gelesene Abschnitt als Entwurf, wie weit der Text danach gelesen ist
     * (0..1) und ob er damit zu Ende gelesen ist (auch wenn die letzte Stelle angetippt wurde).
     */
    data class Section(val attachment: TextAttachment?, val fraction: Float?, val finished: Boolean)

    /**
     * Schneidet aus [text] heraus, was von [start] (null: Anfang) bis [end] (null: Ende) gelesen
     * wurde – als Entwurf für die Aufnahme, damit sie ihren Text auch dann behält, wenn er aus der
     * Bibliothek gelöscht wird. Ganz von vorne bis zum Ende gelesen: eine Kopie des Originals.
     */
    suspend fun section(text: LibraryText, start: ReadingPosition?, end: ReadingPosition?): Section = withContext(Dispatchers.IO) {
        val attachment = attachmentOf(text)?.takeIf { it.files.all(File::isFile) }
            ?: throw IOException("Der Text ist nicht mehr in der Bibliothek.")
        val created = mutableListOf<File>()
        try {
            when (attachment) {
                is TextAttachment.Photo -> photoSection(attachment, start as? ReadingPosition.OnPage, end as? ReadingPosition.OnPage, created)
                is TextAttachment.Document -> if (attachment.kind == DocumentKind.Pdf) {
                    pdfSection(attachment, start as? ReadingPosition.OnPage, end as? ReadingPosition.OnPage, created)
                } else {
                    textSection(text, attachment, start as? ReadingPosition.InText, end as? ReadingPosition.InText, created)
                }
            }
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        }
    }

    private fun photoSection(
        photo: TextAttachment.Photo,
        start: ReadingPosition.OnPage?,
        end: ReadingPosition.OnPage?,
        created: MutableList<File>,
    ): Section {
        val last = photo.pages.lastIndex
        val from = (start?.page ?: 0).coerceIn(0, last)
        val to = (end?.page ?: last).coerceIn(from, last)
        val startY = start?.y?.takeIf { it > 0f }
        val endY = end?.y?.takeIf { it < PAGE_END }?.let { if (to == from && startY != null) it.coerceAtLeast(startY) else it }
        val pages = (from..to).map { index ->
            files.newDraftFile("jpg").also { copy ->
                created += copy
                photo.pages[index].copyTo(copy, overwrite = true)
            }
        }
        val fraction = end?.let { ((to + (endY ?: 1f)) / photo.pages.size) }
        return Section(TextAttachment.Photo(pages, startY, endY), fraction, finished = to == last && endY == null)
    }

    private fun pdfSection(
        document: TextAttachment.Document,
        start: ReadingPosition.OnPage?,
        end: ReadingPosition.OnPage?,
        created: MutableList<File>,
    ): Section {
        if (start == null && end == null) {
            val copy = files.newDraftFile("pdf").also { created += it }
            document.file.copyTo(copy, overwrite = true)
            return Section(TextAttachment.Document(copy, document.name), null, finished = true)
        }
        PdfPages.open(document.file).use { pdf ->
            val last = pdf.pageCount - 1
            val from = (start?.page ?: 0).coerceIn(0, last)
            val to = (end?.page ?: last).coerceIn(from, last)
            val startY = start?.y?.takeIf { it > 0f }
            val endY = end?.y?.takeIf { it < PAGE_END }?.let { if (to == from && startY != null) it.coerceAtLeast(startY) else it }
            val pages = (from..to).map { index ->
                val bitmap = pdf.render(index, PDF_SECTION_WIDTH) ?: throw IOException("Die PDF-Seite ließ sich nicht übernehmen.")
                files.newDraftFile("jpg").also { page ->
                    created += page
                    page.outputStream().use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PDF_SECTION_QUALITY, out)) {
                            throw IOException("Die PDF-Seite ließ sich nicht übernehmen.")
                        }
                    }
                }
            }
            val fraction = end?.let { ((to + (endY ?: 1f)) / pdf.pageCount) }
            return Section(TextAttachment.Photo(pages, startY, endY), fraction, finished = to == last && endY == null)
        }
    }

    private fun textSection(
        text: LibraryText,
        document: TextAttachment.Document,
        start: ReadingPosition.InText?,
        end: ReadingPosition.InText?,
        created: MutableList<File>,
    ): Section {
        if (start == null && end == null) {
            val copy = files.newDraftFile(document.kind.extension).also { created += it }
            document.file.copyTo(copy, overwrite = true)
            return Section(TextAttachment.Document(copy, document.name), null, finished = true)
        }
        val content = DocumentText.read(document.file, document.kind)
        val layout = TextLayout(content)
        val from = TextProgress.remainingStart(layout, start?.offset ?: 0)
        val to = (end?.offset ?: layout.length).coerceIn(from, layout.length)
        val section = TextProgress.section(content, from, to)
        val fraction = end?.let { TextProgress.fraction(layout, to) }
        val finished = TextProgress.isAtEnd(layout, to)
        if (section.isBlank()) return Section(null, fraction, finished)
        val draft = files.newDraftFile(DocumentKind.Markdown.extension).also { created += it }
        draft.writeText(MarkdownWriter.write(section))
        return Section(TextAttachment.Document(draft, "${text.title} (Abschnitt)"), fraction, finished)
    }

    /** Ein Titel für einen neuen Text: der Dateiname ohne Endung bzw. die erste Zeile eines eingefügten Texts. */
    fun suggestedTitle(attachment: TextAttachment, photoTitle: String): String = when (attachment) {
        is TextAttachment.Photo -> photoTitle
        is TextAttachment.Document -> {
            val name = attachment.name
            val extension = attachment.kind.extension
            cleanTitle(if (name.endsWith(".$extension", ignoreCase = true)) name.dropLast(extension.length + 1) else name)
        }
    }

    // --- intern ---

    /** Wohin neue Dateien in der Bibliothek kommen; [undo] macht das Ablegen rückgängig. */
    private class Stored(
        val photoFiles: String?,
        val documentFile: String?,
        val documentName: String?,
        val undo: () -> Unit,
    )

    private fun store(uuid: String, attachment: TextAttachment, move: Boolean): Stored {
        val stamp = System.currentTimeMillis()
        val done = mutableListOf<Pair<File, File>>()
        fun put(source: File, name: String) {
            val target = files.library(name)
            if (move) FileStore.move(source, target) else source.copyTo(target, overwrite = true)
            done += source to target
        }
        val undo = {
            for ((source, target) in done) {
                if (move) runCatching { FileStore.move(target, source) } else target.delete()
            }
        }
        try {
            return when (attachment) {
                is TextAttachment.Photo -> {
                    val names = attachment.pages.mapIndexed { i, page ->
                        "${uuid}_${stamp}_${i + 1}.jpg".also { put(page, it) }
                    }
                    Stored(names.joinPageNames(), null, null, undo)
                }
                is TextAttachment.Document -> {
                    val name = "${uuid}_$stamp.${attachment.kind.extension}"
                    put(attachment.file, name)
                    Stored(null, name, attachment.name, undo)
                }
            }
        } catch (e: Exception) {
            undo()
            throw if (e is IOException) e else IOException("Der Text konnte nicht in der Bibliothek gespeichert werden.", e)
        }
    }

    /** Die gelesene Seite nach dem Bearbeiten: dieselbe Seite an ihrem neuen Platz, gedreht ab oben. */
    private fun movedPage(progress: ReadingPosition.OnPage, origins: List<PageOrigin?>, photoFiles: String?): Pair<ReadingPosition, Float>? {
        val name = progress.pageName ?: return null
        val index = origins.indexOfFirst { it?.name == name }.takeIf { it >= 0 } ?: return null
        val pages = photoFiles?.split('\n').orEmpty()
        val newName = pages.getOrNull(index) ?: return null
        val y = if (origins[index]?.rotated == true) 0f else progress.y
        return ReadingPosition.OnPage(index, y, newName) to ((index + y) / pages.size)
    }

    /** Dieselbe Stelle in einem geänderten Text, oder null, wenn der Satz nicht mehr vorkommt. */
    private fun relocated(progress: ReadingPosition.InText, attachment: TextAttachment): Pair<ReadingPosition, Float>? {
        val document = attachment as? TextAttachment.Document ?: return null
        if (document.kind == DocumentKind.Pdf) return null
        val text = runCatching { DocumentText.read(document.file, document.kind) }.getOrNull() ?: return null
        val resolved = TextProgress.resolve(text, progress) ?: return null
        return resolved to TextProgress.fraction(TextLayout(text), resolved.offset)
    }

    private fun searchContent(text: LibraryText): String {
        val body = (attachmentOf(text) as? TextAttachment.Document)
            ?.takeIf { it.kind != DocumentKind.Pdf }
            ?.let { runCatching { DocumentText.read(it.file, it.kind, maxChars = SEARCH_CHARS).plainText }.getOrNull() }
            .orEmpty()
        return normalize(listOf(text.title, text.tags, body).joinToString("\n"))
    }

    companion object {
        /** So viel Text wird durchsucht – reicht für jeden Lesetext und hält die Datenbank klein. */
        private const val SEARCH_CHARS = 200_000

        /** PDF-Seiten eines Abschnitts werden als Bilder gespeichert: scharf genug zum Lesen, klein genug fürs Backup. */
        private const val PDF_SECTION_WIDTH = 1600
        private const val PDF_SECTION_QUALITY = 85

        /** Eine Linie so weit unten zählt als „bis zum Seitenende“. */
        private const val PAGE_END = 0.98f
        private const val MAX_TITLE = 120

        /** Titel für fotografierte Texte, z. B. „Fotos vom 3. November 2026“. */
        fun photoTitle(attachment: TextAttachment): String {
            val day = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMANY).format(LocalDate.now())
            return if ((attachment as? TextAttachment.Photo)?.pages?.size == 1) "Foto vom $day" else "Fotos vom $day"
        }

        fun normalize(text: String): String = text.lowercase(Locale.GERMAN).replace(Regex("\\s+"), " ").trim()

        fun cleanTitle(title: String): String =
            title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE).ifBlank { "Ohne Titel" }
    }
}
