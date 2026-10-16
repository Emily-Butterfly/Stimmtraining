package de.emilybutterfly.stimmtraining.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Eingefügter (oder getippter) Text, der wie eine Textdatei an einer Aufnahme hängt. */
object PastedText {
    /**
     * Länger ist kaum ein Lesetext (etwa 25 Buchseiten). Die Grenze hält den Bearbeiten-Dialog
     * flüssig und seinen gesicherten Zustand klein genug für Android.
     */
    const val MAX_CHARS = 50_000

    /** Ein Name für die Anzeige: die erste Zeile, gekürzt. */
    fun name(text: String): String {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.replace(Regex("\\s+"), " ")
            ?: return "Eingefügter Text"
        return if (firstLine.length <= 40) firstLine else firstLine.take(39).trimEnd() + "…"
    }

    /** Speichert den Text als UTF-8-Entwurf. */
    fun createDraft(files: FileStore, text: String): File =
        files.newDraftFile("txt").apply { writeText(text.take(MAX_CHARS)) }

    /**
     * Der Inhalt einer Textdatei zum Bearbeiten, oder null, wenn sie zu lang ist oder nicht in
     * UTF-8 vorliegt (dann würden beim Speichern Umlaute kaputtgehen).
     */
    fun readForEditing(file: File): String? {
        if (file.length() > MAX_CHARS * 4L) return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return text.removePrefix("﻿").takeIf { it.length <= MAX_CHARS }
    }
}
