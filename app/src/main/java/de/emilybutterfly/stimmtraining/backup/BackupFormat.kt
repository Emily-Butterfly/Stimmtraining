package de.emilybutterfly.stimmtraining.backup

import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.document.DocumentKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class BackupException(message: String) : Exception(message)

/**
 * Aufbau einer Sicherung (ZIP):
 *
 * ```
 * manifest.json      alle Aufnahmen mit ihren Parametern
 * audio/<datei>      Audiodateien
 * photos/<datei>     Fotos der Texte
 * documents/<datei>  Texte als Datei (PDF, Word, …), seit Version 2
 * ```
 */
object BackupFormat {
    const val FORMAT = "stimmtraining-backup"
    const val VERSION = 2
    const val MANIFEST = "manifest.json"
    const val AUDIO_DIR = "audio/"
    const val PHOTO_DIR = "photos/"
    const val DOCUMENT_DIR = "documents/"

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val SAFE_UUID = Regex("[A-Za-z0-9-]{8,64}")

    /** Eine Aufnahme aus dem Manifest; Dateinamen sind relativ zu audio/, photos/ bzw. documents/. */
    data class Entry(val recording: Recording)

    fun manifest(recordings: List<Recording>, createdAt: Long, appVersion: String): String {
        val list = JSONArray()
        for (r in recordings) {
            list.put(
                JSONObject()
                    .put("uuid", r.uuid)
                    .put("recordedAt", r.recordedAt)
                    .put("durationMs", r.durationMs)
                    .put("audio", AUDIO_DIR + r.audioFile)
                    .put("photo", r.photoFile?.let { PHOTO_DIR + it } ?: JSONObject.NULL)
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
            .toString(2)
    }

    fun parse(json: String): List<Entry> {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw BackupException("Die Sicherung ist beschädigt (manifest.json nicht lesbar).")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupException("Diese Datei ist keine Stimmtraining-Sicherung.")
        }
        if (root.optInt("version", 0) > VERSION) {
            throw BackupException("Die Sicherung stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
        }
        val list = root.optJSONArray("recordings") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::parseEntry) }
    }

    private fun parseEntry(o: JSONObject): Entry? {
        val audio = o.stringOrNull("audio")?.let(::safeEntryName)?.takeIf { it.startsWith(AUDIO_DIR) }
            ?: return null
        val photo = o.stringOrNull("photo")?.let(::safeEntryName)?.takeIf { it.startsWith(PHOTO_DIR) }
        val document = o.stringOrNull("document")?.let(::safeEntryName)
            ?.takeIf { it.startsWith(DOCUMENT_DIR) }
            ?.removePrefix(DOCUMENT_DIR)
        val documentKind = document?.let { DocumentKind.fromExtension(it.substringAfterLast('.', "")) }
        val uuid = o.stringOrNull("uuid")?.takeIf { SAFE_UUID.matches(it) } ?: UUID.randomUUID().toString()
        val recording = Recording(
            uuid = uuid,
            recordedAt = o.optLong("recordedAt", 0L),
            durationMs = o.optLong("durationMs", 0L),
            audioFile = audio.removePrefix(AUDIO_DIR),
            photoFile = photo?.removePrefix(PHOTO_DIR),
            documentFile = document?.takeIf { documentKind != null },
            documentName = documentKind?.let { kind ->
                o.stringOrNull("documentName")?.let(::cleanDisplayName) ?: "Text.${kind.extension}"
            },
            title = o.stringOrNull("title").orEmpty(),
            exercise = o.stringOrNull("exercise").orEmpty(),
            warmedUp = o.optBoolean("warmedUp", false),
            warmUpMinutes = o.intOrNull("warmUpMinutes"),
            voiceCondition = o.intOrNull("voiceCondition")?.takeIf { it in 1..5 },
            selfRating = o.intOrNull("selfRating")?.takeIf { it in 1..5 },
            notes = o.stringOrNull("notes").orEmpty(),
            updatedAt = o.optLong("updatedAt", 0L),
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
            else -> return null
        }
        val file = name.removePrefix(dir)
        return if (SAFE_FILE_NAME.matches(file)) dir + file else null
    }

    /** Der Name wird nur angezeigt, nie als Pfad benutzt; trotzdem ohne Steuerzeichen und kurz. */
    private fun cleanDisplayName(raw: String): String? =
        raw.replace(Regex("\\p{Cntrl}"), "").trim().take(200).ifBlank { null }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)

    private fun JSONObject.intOrNull(name: String): Int? =
        if (isNull(name)) null else optInt(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
}
