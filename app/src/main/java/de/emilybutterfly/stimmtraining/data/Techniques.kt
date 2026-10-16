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

object TechniqueLibrary {
    const val ASSET = "techniques.json"

    fun load(context: Context): TechniqueContent =
        context.assets.open(ASSET).use { parse(it.readBytes().toString(Charsets.UTF_8)) }

    fun parse(json: String): TechniqueContent {
        val root = JSONObject(json)
        return TechniqueContent(
            techniques = root.getJSONArray("techniques").objects().map(::technique),
            sources = root.optJSONArray("sources")?.objects()?.map { Source(it.getString("title"), it.getString("url")) }
                .orEmpty(),
        )
    }

    private fun technique(o: JSONObject) = Technique(
        id = o.getString("id"),
        title = o.getString("title"),
        subtitle = o.getString("subtitle"),
        focus = o.optBoolean("focus", false),
        intro = o.getString("intro"),
        why = o.getString("why"),
        howToFind = o.strings("howToFind"),
        examples = o.strings("examples"),
        exercises = o.getJSONArray("exercises").objects().map(::exercise),
        pitfalls = o.strings("pitfalls"),
        safety = o.strings("safety"),
    )

    private fun exercise(o: JSONObject) = Exercise(
        id = o.getString("id"),
        title = o.getString("title"),
        goal = o.getString("goal"),
        duration = o.getString("duration"),
        steps = o.strings("steps"),
        examples = o.strings("examples"),
        checks = o.strings("checks"),
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.strings(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
}
