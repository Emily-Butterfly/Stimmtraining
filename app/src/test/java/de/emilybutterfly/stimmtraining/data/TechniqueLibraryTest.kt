package de.emilybutterfly.stimmtraining.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prüft die mitgelieferten Inhalte von „Techniken & Übungen“ (assets/techniques.json). */
class TechniqueLibraryTest {
    private val content = TechniqueLibrary.parse(File("src/main/assets/${TechniqueLibrary.ASSET}").readText())

    @Test
    fun twangIsTheFocusTechnique() {
        val twang = content.technique("twang")
        assertTrue("Twang fehlt", twang != null)
        assertTrue(twang!!.focus)
        assertEquals(listOf("twang"), content.techniques.filter { it.focus }.map { it.id })
    }

    @Test
    fun idsAreUniqueAndUrlSafe() {
        val ids = content.techniques.map { it.id }
        assertEquals("doppelte Technik-IDs", ids.distinct(), ids)
        val idPattern = Regex("[a-z0-9-]+")
        for (technique in content.techniques) {
            assertTrue(technique.id, idPattern.matches(technique.id))
            val exerciseIds = technique.exercises.map { it.id }
            assertEquals("doppelte Übungs-IDs in ${technique.id}", exerciseIds.distinct(), exerciseIds)
            exerciseIds.forEach { assertTrue("${technique.id}/$it", idPattern.matches(it)) }
        }
    }

    @Test
    fun everyTechniqueIsComplete() {
        for (t in content.techniques) {
            val texts = listOf(t.title, t.subtitle, t.intro, t.why) + t.howToFind + t.examples + t.pitfalls + t.safety
            assertTrue("${t.id}: leerer Text", texts.none { it.isBlank() })
            assertTrue("${t.id}: keine Übungen", t.exercises.isNotEmpty())
            for (e in t.exercises) {
                val exerciseTexts = listOf(e.title, e.goal, e.duration) + e.steps + e.examples + e.checks
                assertTrue("${t.id}/${e.id}: leerer Text", exerciseTexts.none { it.isBlank() })
                assertTrue("${t.id}/${e.id}: keine Schritte", e.steps.isNotEmpty())
            }
        }
    }

    @Test
    fun sourcesAreWebLinks() {
        assertTrue(content.sources.isNotEmpty())
        content.sources.forEach { assertTrue(it.url, it.url.startsWith("https://") || it.url.startsWith("http://")) }
    }

    @Test
    fun gentleVersionHasNoComparisonsReferenceValuesOrMeasuring() {
        val gentle = TechniqueLibrary.parse(File("src/main/assets/${TechniqueLibrary.ASSET}").readText(), gentle = true)
        val triggers = Regex(
            "weiblich|männlich|trans Frauen|Frauenstimme|Geschlecht|Hz\\b|Hertz|Tonhöhen-App|Stimmgerät|" +
                "als .{0,30} gehört|wahrgenommen|Studie|Forschung",
            RegexOption.IGNORE_CASE,
        )
        for (technique in gentle.techniques) {
            val texts = listOf(technique.intro, technique.why) + technique.howToFind + technique.examples +
                technique.pitfalls + technique.safety +
                technique.exercises.flatMap { listOf(it.goal) + it.steps + it.examples + it.checks }
            for (text in texts) {
                assertTrue("${technique.id}: „$text“", triggers.find(text) == null)
            }
        }
        assertTrue("keine Quellenliste", gentle.sources.isEmpty())
    }

    @Test
    fun gentleVersionKeepsEveryTechniqueAndExercise() {
        val gentle = TechniqueLibrary.parse(File("src/main/assets/${TechniqueLibrary.ASSET}").readText(), gentle = true)
        // Gleiche IDs und Titel: Aufnahmen bleiben ihrer Übung zugeordnet, egal in welchem Modus.
        assertEquals(
            content.techniques.map { t -> t.id to t.exercises.map { it.id to it.title } },
            gentle.techniques.map { t -> t.id to t.exercises.map { it.id to it.title } },
        )
        gentle.techniques.flatMap { it.exercises }.forEach { assertTrue("${it.id} ohne Schritte", it.steps.isNotEmpty()) }
    }

    @Test
    fun everyGentleFieldReplacesAnExistingField() {
        val root = org.json.JSONObject(File("src/main/assets/${TechniqueLibrary.ASSET}").readText())
        fun check(o: org.json.JSONObject, where: String) {
            o.keys().asSequence().filter { it.endsWith("Gentle") }.forEach { key ->
                assertTrue("$where: $key ersetzt kein vorhandenes Feld (Tippfehler?)", o.has(key.removeSuffix("Gentle")))
            }
        }
        val techniques = root.getJSONArray("techniques")
        for (i in 0 until techniques.length()) {
            val t = techniques.getJSONObject(i)
            check(t, t.getString("id"))
            val exercises = t.getJSONArray("exercises")
            for (j in 0 until exercises.length()) check(exercises.getJSONObject(j), exercises.getJSONObject(j).getString("id"))
        }
    }
}
