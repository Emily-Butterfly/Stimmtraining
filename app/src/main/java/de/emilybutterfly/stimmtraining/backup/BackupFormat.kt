package de.emilybutterfly.stimmtraining.backup

import de.emilybutterfly.stimmtraining.data.takeWhole
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import de.emilybutterfly.stimmtraining.data.joinPageNames
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
import de.emilybutterfly.stimmtraining.data.library.LibraryText
import de.emilybutterfly.stimmtraining.data.library.ReadingPosition
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class BackupException(message: String) : Exception(message)

/**
 * Aufbau einer Sicherung (ZIP):
 *
 * ```
 * manifest.json      alle Aufnahmen mit ihren Parametern, seit Version 3 auch die Bibliothek
 * audio/<datei>      Audiodateien
 * photos/<datei>     Fotos der Texte (seit Version 3 mehrere Seiten pro Aufnahme)
 * documents/<datei>  Texte als Datei (PDF, Word, …), seit Version 2
 * library/<datei>    Fotos und Dateien der Bibliothek, seit Version 3
 * ```
 */
object BackupFormat {
    const val FORMAT = "stimmtraining-backup"
    const val VERSION = 3
    const val MANIFEST = "manifest.json"
    const val AUDIO_DIR = "audio/"
    const val PHOTO_DIR = "photos/"
    const val DOCUMENT_DIR = "documents/"
    const val LIBRARY_DIR = "library/"

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    // Beginnt wie ein sicherer Dateiname, denn die App benennt Dateien nach der UUID.
    private val SAFE_UUID = Regex("[A-Za-z0-9][A-Za-z0-9-]{7,63}")

    /** Eine Aufnahme aus dem Manifest; Dateinamen sind relativ zu audio/, photos/ bzw. documents/. */
    data class Entry(val recording: Recording)

    /** Ein Text der Bibliothek aus dem Manifest; Dateinamen sind relativ zu library/. */
    data class LibraryEntry(val text: LibraryText)

    fun manifest(recordings: List<Recording>, createdAt: Long, appVersion: String, library: List<LibraryText> = emptyList()): String {
        val list = JSONArray()
        for (r in recordings) {
            list.put(
                JSONObject()
                    .put("uuid", r.uuid)
                    .put("recordedAt", r.recordedAt)
                    .put("durationMs", r.durationMs)
                    .put("audio", AUDIO_DIR + r.audioFile)
                    .put("photos", JSONArray(r.photoPages.map { PHOTO_DIR + it }))
                    .put("section", sectionJson(r))
                    .put("document", r.documentFile?.let { DOCUMENT_DIR + it } ?: JSONObject.NULL)
                    .put("documentName", r.documentName ?: JSONObject.NULL)
                    .put("pitch", pitchJson(r))
                    .put("title", r.title)
                    .put("exercise", r.exercise)
                    .put("warmedUp", r.warmedUp)
                    .put("warmUpMinutes", r.warmUpMinutes ?: JSONObject.NULL)
                    .put("voiceCondition", r.voiceCondition ?: JSONObject.NULL)
                    .put("selfRating", r.selfRating ?: JSONObject.NULL)
                    .put("notes", r.notes)
                    .put("updatedAt", r.updatedAt),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", createdAt)
            .put("appVersion", appVersion)
            .put("recordings", list)
            .put("library", JSONArray(library.map(::libraryJson)))
            .toString(2)
    }

    private fun sectionJson(r: Recording): Any =
        if (r.sectionStartY == null && r.sectionEndY == null) {
            JSONObject.NULL
        } else {
            JSONObject().put("startY", r.sectionStartY?.toDouble() ?: JSONObject.NULL).put("endY", r.sectionEndY?.toDouble() ?: JSONObject.NULL)
        }

    private fun libraryJson(t: LibraryText): JSONObject = JSONObject()
        .put("uuid", t.uuid)
        .put("title", t.title)
        .put("tags", JSONArray(t.tagList))
        .put("photos", JSONArray(t.photoPages.map { LIBRARY_DIR + it }))
        .put("document", t.documentFile?.let { LIBRARY_DIR + it } ?: JSONObject.NULL)
        .put("documentName", t.documentName ?: JSONObject.NULL)
        .put("pinned", t.pinned)
        .put("createdAt", t.createdAt)
        .put("updatedAt", t.updatedAt)
        .put("finishedAt", t.finishedAt ?: JSONObject.NULL)
        .put("lastReadAt", t.lastReadAt ?: JSONObject.NULL)
        .put(
            "progress",
            when (val p = t.progress) {
                is ReadingPosition.InText -> JSONObject().put("offset", p.offset).put("anchor", p.anchor)
                is ReadingPosition.OnPage -> JSONObject().put("page", p.page).put("y", p.y.toDouble()).put("pageName", p.pageName ?: JSONObject.NULL)
                null -> JSONObject.NULL
            },
        )
        .put("progressFraction", t.progressFraction?.toDouble() ?: JSONObject.NULL)

    /** Aufnahmen und Bibliothek einer Sicherung, in einem Durchgang gelesen. */
    class Parsed(val recordings: List<Entry>, val library: List<LibraryEntry>)

    fun parseAll(json: String): Parsed {
        val root = parseRoot(json)
        return Parsed(recordings(root), library(root))
    }

    /** Die Texte der Bibliothek aus einer Sicherung (ältere Sicherungen haben keine). */
    fun parseLibrary(json: String): List<LibraryEntry> = library(parseRoot(json))

    private fun library(root: JSONObject): List<LibraryEntry> {
        val list = root.optJSONArray("library") ?: return emptyList()
        return (0 until minOf(list.length(), MAX_ITEMS)).mapNotNull { i -> list.optJSONObject(i)?.let(::parseLibraryEntry) }
    }

    /** Die Zeichenketten einer Liste, höchstens [max] Einträge – mehr liest eine echte Sicherung nie. */
    private fun JSONArray.strings(max: Int): List<String> = (0 until minOf(length(), max)).mapNotNull { opt(it) as? String }

    private fun parseLibraryEntry(o: JSONObject): LibraryEntry? {
        val photos = o.optJSONArray("photos")?.let { array ->
            array.strings(MAX_LIST)
                .mapNotNull(::safeEntryName)
                .filter { it.startsWith(LIBRARY_DIR) }
                .map { it.removePrefix(LIBRARY_DIR) }
                .distinct()
                .take(TextAttachment.MAX_PAGES)
        }.orEmpty()
        val document = o.stringOrNull("document")?.let(::safeEntryName)?.takeIf { it.startsWith(LIBRARY_DIR) }?.removePrefix(LIBRARY_DIR)
        val documentKind = document?.let { DocumentKind.fromExtension(it.substringAfterLast('.', "")) }
        if (photos.isEmpty() && documentKind == null) return null
        val uuid = o.stringOrNull("uuid")?.takeIf { SAFE_UUID.matches(it) } ?: UUID.randomUUID().toString()
        val tags = o.optJSONArray("tags")?.strings(MAX_LIST).orEmpty()
        val createdAt = notInFuture(o.optLong("createdAt", 0L))
        val base = LibraryText(
            uuid = uuid,
            title = LibraryRepository.cleanTitle(o.stringOrNull("title")?.let(::cleanDisplayName).orEmpty()),
            tags = LibraryText.joinTags(tags.map { it.replace(Regex("\\p{Cntrl}"), "") }.take(MAX_TAGS)),
            photoFiles = if (documentKind != null) null else photos.joinPageNames(),
            documentFile = document?.takeIf { documentKind != null },
            documentName = documentKind?.let { kind -> o.stringOrNull("documentName")?.let(::cleanDisplayName) ?: "Text.${kind.extension}" },
            pinned = o.optBoolean("pinned", false),
            createdAt = createdAt,
            updatedAt = notInFuture(o.optLong("updatedAt", createdAt)),
            finishedAt = o.longOrNull("finishedAt")?.let(::notInFuture),
            lastReadAt = o.longOrNull("lastReadAt")?.let(::notInFuture),
        )
        val fraction = o.doubleOrNull("progressFraction")?.toFloat()?.takeIf { it in 0f..1f }
        val progress = o.optJSONObject("progress")?.takeIf { base.finishedAt == null }?.let { p ->
            val offset = p.intOrNull("offset")
            val anchor = p.stringOrNull("anchor")?.takeWhole(MAX_ANCHOR)
            val page = p.intOrNull("page")
            val y = p.doubleOrNull("y")?.toFloat()
            when {
                offset != null && offset >= 0 && !anchor.isNullOrBlank() && base.documentFile != null -> ReadingPosition.InText(offset, anchor)
                page != null && page >= 0 && y != null && y in 0f..1f ->
                    ReadingPosition.OnPage(page, y, p.stringOrNull("pageName")?.takeIf { it in photos })
                else -> null
            }
        }
        return LibraryEntry(base.withProgress(progress, fraction))
    }

    /**
     * Zeitstempel aus der Zukunft (z. B. von einem Handy mit falsch gestellter Uhr) würden die
     * Änderungserkennung des automatischen Backups dauerhaft blind machen.
     */
    private fun notInFuture(millis: Long): Long = millis.coerceIn(0L, System.currentTimeMillis())

    private fun parseRoot(json: String): JSONObject {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw BackupException("Die Sicherung ist beschädigt (manifest.json nicht lesbar).")
        } catch (_: StackOverflowError) {
            // org.json liest verschachtelte Listen rekursiv; tausendfach verschachtelt sprengt das den Stapel.
            throw BackupException("Die Sicherung ist beschädigt (manifest.json nicht lesbar).")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupException("Diese Datei ist keine Stimmtraining-Sicherung.")
        }
        if (root.optInt("version", 0) > VERSION) {
            throw BackupException("Die Sicherung stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
        }
        return root
    }

    fun parse(json: String): List<Entry> = recordings(parseRoot(json))

    private fun recordings(root: JSONObject): List<Entry> {
        val list = root.optJSONArray("recordings") ?: JSONArray()
        return (0 until minOf(list.length(), MAX_ITEMS)).mapNotNull { i -> list.optJSONObject(i)?.let(::parseEntry) }
    }

    private fun parseEntry(o: JSONObject): Entry? {
        val audio = o.stringOrNull("audio")?.let(::safeEntryName)?.takeIf { it.startsWith(AUDIO_DIR) }
            ?: return null
        // Seit Version 3 eine Liste von Seiten, davor genau ein Foto.
        val photos = (
            o.optJSONArray("photos")?.strings(MAX_LIST)
                ?: listOfNotNull(o.stringOrNull("photo"))
            )
            .mapNotNull(::safeEntryName)
            .filter { it.startsWith(PHOTO_DIR) }
            .map { it.removePrefix(PHOTO_DIR) }
            .distinct()
            .take(TextAttachment.MAX_PAGES)
        val section = o.optJSONObject("section")
        val document = o.stringOrNull("document")?.let(::safeEntryName)
            ?.takeIf { it.startsWith(DOCUMENT_DIR) }
            ?.removePrefix(DOCUMENT_DIR)
        val documentKind = document?.let { DocumentKind.fromExtension(it.substringAfterLast('.', "")) }
        val uuid = o.stringOrNull("uuid")?.takeIf { SAFE_UUID.matches(it) } ?: UUID.randomUUID().toString()
        val recording = Recording(
            uuid = uuid,
            // Unsinnige Werte nicht übernehmen: Sortierung und Anzeige gerieten sonst durcheinander.
            recordedAt = o.optLong("recordedAt", 0L).coerceIn(0L, System.currentTimeMillis() + MAX_FUTURE_MS),
            durationMs = o.optLong("durationMs", 0L).coerceIn(0L, MAX_DURATION_MS),
            audioFile = audio.removePrefix(AUDIO_DIR),
            photoFile = photos.joinPageNames(),
            documentFile = document?.takeIf { documentKind != null },
            sectionStartY = section?.doubleOrNull("startY")?.toFloat()?.takeIf { it in 0f..1f && photos.isNotEmpty() },
            sectionEndY = section?.doubleOrNull("endY")?.toFloat()?.takeIf { it in 0f..1f && photos.isNotEmpty() },
            documentName = documentKind?.let { kind ->
                o.stringOrNull("documentName")?.let(::cleanDisplayName) ?: "Text.${kind.extension}"
            },
            title = Recording.cleanText(o.stringOrNull("title").orEmpty(), Recording.MAX_TITLE),
            exercise = Recording.cleanText(o.stringOrNull("exercise").orEmpty(), Recording.MAX_EXERCISE),
            warmedUp = o.optBoolean("warmedUp", false),
            warmUpMinutes = o.intOrNull("warmUpMinutes"),
            voiceCondition = o.intOrNull("voiceCondition")?.takeIf { it in 1..5 },
            selfRating = o.intOrNull("selfRating")?.takeIf { it in 1..5 },
            notes = Recording.cleanText(o.stringOrNull("notes").orEmpty(), Recording.MAX_NOTES),
            updatedAt = notInFuture(o.optLong("updatedAt", 0L)),
        ).withPitch(o.optJSONObject("pitch"))
        return Entry(recording)
    }

    private fun pitchJson(r: Recording): Any = r.pitchVoicedMs?.let { voicedMs ->
        JSONObject()
            .put("mostFrequent", r.pitchMostFrequent ?: JSONObject.NULL)
            .put("lowest", r.pitchLowest ?: JSONObject.NULL)
            .put("highest", r.pitchHighest ?: JSONObject.NULL)
            .put("voicedMs", voicedMs)
    } ?: JSONObject.NULL

    /** Übernimmt die Tonhöhen-Analyse nur, wenn sie vollständig und plausibel ist; sonst lässt sie sich neu berechnen. */
    private fun Recording.withPitch(pitch: JSONObject?): Recording {
        if (pitch == null) return this
        val voicedMs = pitch.optLong("voicedMs", -1L).takeIf { it >= 0 } ?: return this
        val notes = listOf("mostFrequent", "lowest", "highest").map { pitch.intOrNull(it) }
        if (notes.any { it != null && it !in 0..127 }) return this
        if (notes.any { it == null } && notes.any { it != null }) return this
        // Reihenfolge tiefste ≤ häufigste ≤ höchste muss stimmen.
        if (notes[0] != null && !(notes[1]!! <= notes[0]!! && notes[0]!! <= notes[2]!!)) return this
        return copy(pitchMostFrequent = notes[0], pitchLowest = notes[1], pitchHighest = notes[2], pitchVoicedMs = voicedMs)
    }

    /**
     * Normalisiert den Namen eines ZIP-Eintrags. Liefert null für alles, was nicht zum
     * Format gehört, insbesondere für Pfade, die aus dem Zielordner herausführen würden.
     */
    fun safeEntryName(raw: String): String? {
        val name = raw.replace('\\', '/').trimStart('/')
        if (name == MANIFEST) return name
        val dir = when {
            name.startsWith(AUDIO_DIR) -> AUDIO_DIR
            name.startsWith(PHOTO_DIR) -> PHOTO_DIR
            name.startsWith(DOCUMENT_DIR) -> DOCUMENT_DIR
            name.startsWith(LIBRARY_DIR) -> LIBRARY_DIR
            else -> return null
        }
        val file = name.removePrefix(dir)
        return if (SAFE_FILE_NAME.matches(file)) dir + file else null
    }

    /** Der Name wird nur angezeigt, nie als Pfad benutzt; trotzdem ohne Steuerzeichen und kurz. */
    private fun cleanDisplayName(raw: String): String? =
        raw.replace(Regex("\\p{Cntrl}"), "").trim().takeWhole(200).ifBlank { null }

    /** Nur echte Zeichenketten: optString würde Listen und Objekte in Text umwandeln, verschachtelt sogar rekursiv. */
    private fun JSONObject.stringOrNull(name: String): String? = opt(name) as? String

    private fun JSONObject.intOrNull(name: String): Int? =
        if (isNull(name)) null else optInt(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONObject.longOrNull(name: String): Long? =
        if (isNull(name)) null else optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (isNull(name)) null else optDouble(name, Double.NaN).takeIf { !it.isNaN() }

    private const val MAX_TAGS = 30

    /** Mehr Aufnahmen bzw. Texte und mehr Listeneinträge (Seiten, Schlagworte) liest eine echte Sicherung nie. */
    private const val MAX_ITEMS = 100_000
    private const val MAX_LIST = 200
    private const val MAX_FUTURE_MS = 366L * 24 * 60 * 60 * 1000
    private const val MAX_DURATION_MS = 48L * 60 * 60 * 1000
    private const val MAX_ANCHOR = 300
}
