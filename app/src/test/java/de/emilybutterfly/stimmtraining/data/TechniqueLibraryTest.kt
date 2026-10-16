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
}
