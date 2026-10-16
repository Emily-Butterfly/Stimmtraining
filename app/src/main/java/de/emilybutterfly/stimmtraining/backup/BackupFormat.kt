package de.emilybutterfly.stimmtraining.backup

import de.emilybutterfly.stimmtraining.data.Recording
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
 * ```
 */
object BackupFormat {
    const val FORMAT = "stimmtraining-backup"
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val AUDIO_DIR = "audio/"
    const val PHOTO_DIR = "photos/"

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val SAFE_UUID = Regex("[A-Za-z0-9-]{8,64}")

    /** Eine Aufnahme aus dem Manifest; Dateinamen sind relativ zu audio/ bzw. photos/. */
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
        val uuid = o.stringOrNull("uuid")?.takeIf { SAFE_UUID.matches(it) } ?: UUID.randomUUID().toString()
        val recording = Recording(
            uuid = uuid,
            recordedAt = o.optLong("recordedAt", 0L),
            durationMs = o.optLong("durationMs", 0L),
            audioFile = audio.removePrefix(AUDIO_DIR),
            photoFile = photo?.removePrefix(PHOTO_DIR),
            title = o.stringOrNull("title").orEmpty(),
            exercise = o.stringOrNull("exercise").orEmpty(),
            warmedUp = o.optBoolean("warmedUp", false),
            warmUpMinutes = o.intOrNull("warmUpMinutes"),
            voiceCondition = o.intOrNull("voiceCondition")?.takeIf { it in 1..5 },
            selfRating = o.intOrNull("selfRating")?.takeIf { it in 1..5 },
            notes = o.stringOrNull("notes").orEmpty(),
            updatedAt = o.optLong("updatedAt", 0L),
        )
        return Entry(recording)
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
            else -> return null
        }
        val file = name.removePrefix(dir)
        return if (SAFE_FILE_NAME.matches(file)) dir + file else null
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)

    private fun JSONObject.intOrNull(name: String): Int? =
        if (isNull(name)) null else optInt(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
}
