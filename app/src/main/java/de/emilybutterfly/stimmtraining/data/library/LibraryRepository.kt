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
            // Das Lesen dauert; erst danach ablegen, damit die Dateien nur kurz ohne Datenbankzeile sind.
            val body = searchBody(attachment)
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
                dao.insertWithSearch(text, searchContent(text, body))
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
        dao.updateWithSearch(updated, searchContent(updated))
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
        // Alles Langsame (Text lesen, Stelle suchen) vor dem Ablegen der Dateien, und dann Zeile
        // und Suchinhalt in einem Schritt: So zeigt nie etwas auf halb Gespeichertes.
        val body = searchBody(replacement)
        val progress = current.progress
        val relocatedText = (progress as? ReadingPosition.InText)?.let { relocated(it, replacement) }
        val stored = store(current.uuid, replacement, move = true)
        val lost: Boolean
        try {
            val moved = when (progress) {
                null -> null
                is ReadingPosition.OnPage -> movedPage(progress, origins, stored.photoFiles)
                is ReadingPosition.InText -> relocatedText
            }
            lost = progress != null && moved == null
            val result = current.copy(
                title = cleanTitle(title),
                tags = LibraryText.joinTags(tags),
                photoFiles = stored.photoFiles,
                documentFile = stored.documentFile,
                documentName = stored.documentName,
                updatedAt = System.currentTimeMillis(),
            ).withProgress(moved?.first, moved?.second)
            dao.updateWithSearch(result, searchContent(result, body))
        } catch (e: Exception) {
            stored.undo()
            throw e
        }
        val kept = (stored.photoFiles?.split('\n').orEmpty() + listOfNotNull(stored.documentFile)).toSet()
        (current.photoPages + listOfNotNull(current.documentFile)).filter { it !in kept }.forEach { files.library(it).delete() }
        lost
    }

    /** Setzt die gelesene Stelle (z. B. von Hand korrigiert); null beginnt den Text von vorne. */
    suspend fun setProgress(id: Long, position: ReadingPosition?) = withContext(Dispatchers.IO) {
        val current = dao.get(id) ?: return@withContext
        val now = System.currentTimeMillis()
        val measured = position?.let { measure(current, it) }
        // Bis zum letzten Satz bzw. Seitenende gesetzt: Dann ist der Text zu Ende gelesen.
        val updated = if (measured?.atEnd == true) {
            current.withProgress(null, null).copy(finishedAt = now)
        } else {
            current.withProgress(position, measured?.fraction).copy(finishedAt = null)
        }
        dao.update(updated.copy(updatedAt = now))
    }

    private class Measured(val fraction: Float?, val atEnd: Boolean)

    /** Wie viel bis [position] gelesen ist (0..1), für die Anzeige in der Liste, und ob damit alles gelesen ist. */
    private fun measure(text: LibraryText, position: ReadingPosition): Measured? {
        val attachment = attachmentOf(text) ?: return null
        return when (position) {
            is ReadingPosition.InText -> (attachment as? TextAttachment.Document)?.takeIf { it.kind != DocumentKind.Pdf }
                ?.let { runCatching { DocumentText.read(it.file, it.kind) }.getOrNull() }
                ?.let { TextLayout(it) }
                ?.let {
                    Measured(TextProgress.fraction(it, position.offset), TextProgress.isAtEnd(it, position.offset) && !it.document.truncated)
                }
            is ReadingPosition.OnPage -> {
                val pages = when (attachment) {
                    is TextAttachment.Photo -> attachment.pages.size
                    is TextAttachment.Document -> runCatching { PdfPages.open(attachment.file).use { it.pageCount } }.getOrNull()
                } ?: return null
                Measured(((position.page + position.y) / pages).coerceIn(0f, 1f), position.page >= pages - 1 && position.y >= LAST_LINES)
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
                    when {
                        index == null || index !in attachment.pages.indices -> StartPoint(null, lost = true)
                        // Bis zum Seitenende gelesen: weiter oben auf der nächsten Seite statt auf einer ganz grauen.
                        progress.y >= PAGE_END && index < attachment.pages.lastIndex ->
                            StartPoint(ReadingPosition.OnPage(index + 1, 0f, attachment.pages[index + 1].name))
                        else -> StartPoint(progress.copy(page = index, pageName = attachment.pages[index].name))
                    }
                }
                is TextAttachment.Document -> when {
                    attachment.kind != DocumentKind.Pdf -> StartPoint(null, lost = true)
                    progress.y >= PAGE_END -> StartPoint(ReadingPosition.OnPage(progress.page + 1, 0f))
                    else -> StartPoint(progress)
                }
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
        return Section(TextAttachment.Photo(pages, startY, endY), fraction, finished = to == last && (endY ?: 1f) >= LAST_LINES)
    }

    private fun pdfSection(
        document: TextAttachment.Document,
        start: ReadingPosition.OnPage?,
        end: ReadingPosition.OnPage?,
        created: MutableList<File>,
    ): Section {
        fun wholeDocument(fraction: Float?, finished: Boolean): Section {
            val copy = files.newDraftFile("pdf").also { created += it }
            document.file.copyTo(copy, overwrite = true)
            return Section(TextAttachment.Document(copy, document.name), fraction, finished)
        }
        if (start == null && end == null) return wholeDocument(null, finished = true)
        PdfPages.open(document.file).use { pdf ->
            if (pdf.pageCount == 0) return wholeDocument(null, finished = true)
            val last = pdf.pageCount - 1
            val from = (start?.page ?: 0).coerceIn(0, last)
            val to = (end?.page ?: last).coerceIn(from, last)
            // Mehr Seiten als ein Anhang fasst: dann das ganze PDF statt eines riesigen Stapels Bilder.
            if (to - from + 1 > TextAttachment.MAX_PAGES) {
                val endY = end?.y?.takeIf { it < PAGE_END }
                return wholeDocument(end?.let { (to + (endY ?: 1f)) / pdf.pageCount }, finished = to == last && (endY ?: 1f) >= LAST_LINES)
            }
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
            return Section(TextAttachment.Photo(pages, startY, endY), fraction, finished = to == last && (endY ?: 1f) >= LAST_LINES)
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
        // Bei einem gekürzten Text ist das angezeigte Ende nicht das Ende des Texts.
        val finished = TextProgress.isAtEnd(layout, to) && !content.truncated
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
            try {
                if (move) FileStore.move(source, target) else source.copyTo(target, overwrite = true)
            } catch (e: Exception) {
                // Eine halb geschriebene Kopie (z. B. Speicher voll) nicht liegen lassen; das Original ist noch da.
                target.delete()
                throw e
            }
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

    private fun searchContent(text: LibraryText, body: String = searchBody(attachmentOf(text))): String =
        normalize(listOf(text.title, text.tags, body).joinToString("\n"))

    /** Der durchsuchbare Text eines Anhangs; Fotos und PDFs haben keinen. */
    private fun searchBody(attachment: TextAttachment?): String = (attachment as? TextAttachment.Document)
        ?.takeIf { it.kind != DocumentKind.Pdf }
        ?.let { runCatching { DocumentText.read(it.file, it.kind, maxChars = SEARCH_CHARS).plainText }.getOrNull() }
        .orEmpty()

    /**
     * Ist der Text länger, als die App anzeigt (etwa 150 Buchseiten)? Dann lässt sich nur der
     * Anfang lesen – das sollte man beim Ablegen in der Bibliothek erfahren.
     */
    suspend fun isShortened(attachment: TextAttachment): Boolean = withContext(Dispatchers.IO) {
        val document = attachment as? TextAttachment.Document ?: return@withContext false
        document.kind != DocumentKind.Pdf && runCatching { DocumentText.read(document.file, document.kind).truncated }.getOrDefault(false)
    }

    /**
     * Räumt Dateien in der Bibliothek weg, auf die kein Text mehr zeigt (z. B. weil die App beim
     * Ablegen beendet wurde). Nur ältere, damit nichts gerade Abgelegtes verschwindet.
     */
    suspend fun cleanOrphans() = withContext(Dispatchers.IO) {
        val referenced = dao.getAll().flatMap { it.photoPages + listOfNotNull(it.documentFile) }.toSet()
        val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
        files.libraryDir.listFiles()
            ?.filter { it.isFile && it.name !in referenced && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    companion object {
        /** So viel Text wird durchsucht – reicht für jeden Lesetext und hält die Datenbank klein. */
        private const val SEARCH_CHARS = 200_000

        /** PDF-Seiten eines Abschnitts werden als Bilder gespeichert: scharf genug zum Lesen, klein genug fürs Backup. */
        private const val PDF_SECTION_WIDTH = 1600
        private const val PDF_SECTION_QUALITY = 85

        /** Eine Linie so weit unten zählt als „bis zum Seitenende“. */
        private const val PAGE_END = 0.98f

        /**
         * Ab hier zählt eine Linie auf der letzten Seite als „bis zum Ende gelesen“: Die letzte
         * Zeile steht wegen des Seitenrands selten ganz unten.
         */
        const val LAST_LINES = 0.85f
        private const val MAX_TITLE = 120

        /** Hinweis, wenn ein Text länger ist, als die App anzeigt (siehe [isShortened]). */
        const val SHORTENED =
            "Der Text ist sehr lang: Angezeigt und gelesen werden nur die ersten etwa 150 Buchseiten. Teile ihn für den Rest auf."

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
