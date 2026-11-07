package de.emilybutterfly.stimmtraining.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTextTest {
    @Test
    fun tagsAreSplitCleanedAndDeduplicatedIgnoringCase() {
        assertEquals(listOf("Gedicht", "Lyrik", "Rilke"), LibraryText.splitTags(" Gedicht ,Lyrik;  Rilke ;"))
        assertEquals("Gedicht\nLyrik", LibraryText.joinTags(listOf("Gedicht", "gedicht", "Lyrik, GEDICHT")))
        assertEquals(emptyList<String>(), LibraryText.splitTags(" , ; "))
    }

    @Test
    fun longTagsAreCutWithoutSplittingAnEmoji() {
        val tag = "a".repeat(LibraryText.MAX_TAG_LENGTH - 1) + "😀"
        val cut = LibraryText.splitTags(tag).single()
        assertEquals("a".repeat(LibraryText.MAX_TAG_LENGTH - 1), cut)
    }

    @Test
    fun titlesAreCutWithoutSplittingAnEmoji() {
        val title = "b".repeat(LibraryRepository.MAX_TITLE - 1) + "😀"
        assertEquals("b".repeat(LibraryRepository.MAX_TITLE - 1), LibraryRepository.cleanTitle(title))
    }
}
