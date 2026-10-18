package de.emilybutterfly.stimmtraining.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Inhalte des Bereichs „Techniken & Übungen“ (aus assets/techniques.json). */
data class TechniqueContent(val techniques: List<Technique>, val sources: List<Source>) {
    fun technique(id: String): Technique? = techniques.firstOrNull { it.id == id }
}

data class Technique(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Schwerpunkt-Technik, wird in der Übersicht hervorgehoben. */
    val focus: Boolean,
    val intro: String,
    val why: String,
    val howToFind: List<String>,
    val examples: List<String>,
    val exercises: List<Exercise>,
    val pitfalls: List<String>,
    val safety: List<String>,
) {
    fun exercise(id: String): Exercise? = exercises.firstOrNull { it.id == id }

    /** So wird die Übung bei einer Aufnahme eingetragen. */
    fun recordingLabel(exercise: Exercise) = "$title: ${exercise.title}"
}

data class Exercise(
    val id: String,
    val title: String,
    val goal: String,
    val duration: String,
    val steps: List<String>,
    val examples: List<String>,
    val checks: List<String>,
)

data class Source(val title: String, val url: String)

/**
 * Liest die Inhalte. Mit [gentle] (sanfter Modus) gelten die Felder „…Gentle“, wo es sie gibt:
 * Fassungen ohne Richtwerte, ohne Studien dazu, wie Stimmen nach Geschlecht eingeordnet werden,
 * und ohne Messen mit Stimmgerät-Apps. Titel und IDs sind in beiden Fassungen gleich; die
 * Quellenliste entfällt im sanften Modus.
 */
object TechniqueLibrary {
    const val ASSET = "techniques.json"

    fun load(context: Context, gentle: Boolean = false): TechniqueContent =
        context.assets.open(ASSET).use { parse(it.readBytes().toString(Charsets.UTF_8), gentle) }

    fun parse(json: String, gentle: Boolean = false): TechniqueContent {
        val root = JSONObject(json)
        return TechniqueContent(
            techniques = root.getJSONArray("techniques").objects().map { technique(it, gentle) },
            sources = if (gentle) {
                emptyList()
            } else {
                root.optJSONArray("sources")?.objects()?.map { Source(it.getString("title"), it.getString("url")) }.orEmpty()
            },
        )
    }

    private fun technique(o: JSONObject, gentle: Boolean) = Technique(
        id = o.getString("id"),
        title = o.getString("title"),
        subtitle = o.getString("subtitle"),
        focus = o.optBoolean("focus", false),
        intro = o.text("intro", gentle),
        why = o.text("why", gentle),
        howToFind = o.strings("howToFind", gentle),
        examples = o.strings("examples", gentle),
        exercises = o.getJSONArray("exercises").objects().map { exercise(it, gentle) },
        pitfalls = o.strings("pitfalls", gentle),
        safety = o.strings("safety", gentle),
    )

    private fun exercise(o: JSONObject, gentle: Boolean) = Exercise(
        id = o.getString("id"),
        title = o.getString("title"),
        goal = o.text("goal", gentle),
        duration = o.getString("duration"),
        steps = o.strings("steps", gentle),
        examples = o.strings("examples", gentle),
        checks = o.strings("checks", gentle),
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.text(name: String, gentle: Boolean): String =
        if (gentle && has(name + GENTLE)) getString(name + GENTLE) else getString(name)

    private fun JSONObject.strings(name: String, gentle: Boolean): List<String> {
        val array = (if (gentle) optJSONArray(name + GENTLE) else null) ?: optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    private const val GENTLE = "Gentle"
}
