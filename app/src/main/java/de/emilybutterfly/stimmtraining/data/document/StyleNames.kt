package de.emilybutterfly.stimmtraining.data.document

import java.util.Locale

/**
 * Ordnet Absatzformate aus Word, LibreOffice und RTF einer [BlockStyle] zu – anhand der
 * Kennung ("Heading1", deutsches Word: "berschrift1") oder des Namens ("heading 1",
 * "Heading_20_1", "Überschrift 1", "Title", "Quotations", "Zitat").
 */
internal object StyleNames {
    private val HEADING = Regex("(?:heading|berschrift)([1-9])")

    fun blockStyle(name: String?): BlockStyle? {
        if (name.isNullOrBlank()) return null
        val key = name.replace("_20_", "").lowercase(Locale.ROOT).filterNot { it == ' ' || it == '_' || it == '-' }
        return when {
            key == "title" || key == "titel" -> BlockStyle.Heading1
            key == "subtitle" || key == "untertitel" -> BlockStyle.Heading2
            "quot" in key || "zitat" in key -> BlockStyle.Quote
            else -> HEADING.find(key)?.let { heading(it.groupValues[1].toInt()) }
        }
    }

    /** Gliederungsebene ab 1. */
    fun heading(level: Int): BlockStyle = when {
        level <= 1 -> BlockStyle.Heading1
        level == 2 -> BlockStyle.Heading2
        else -> BlockStyle.Heading3
    }

    val BlockStyle.isHeading: Boolean
        get() = this == BlockStyle.Heading1 || this == BlockStyle.Heading2 || this == BlockStyle.Heading3
}
