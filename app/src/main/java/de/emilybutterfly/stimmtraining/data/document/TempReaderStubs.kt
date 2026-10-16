package de.emilybutterfly.stimmtraining.data.document

import java.io.File

// VORLÄUFIG: wird durch die echten Leser ersetzt.
object DocumentDetector {
    fun detect(file: File, displayName: String?, mimeType: String?): DocumentKind =
        DocumentKind.fromExtension(displayName?.substringAfterLast('.', "") ?: "") ?: DocumentKind.PlainText
}

object DocumentText {
    fun read(file: File, kind: DocumentKind, maxChars: Int = TextDocumentBuilder.DEFAULT_MAX_CHARS): TextDocument {
        val builder = TextDocumentBuilder(maxChars)
        file.readText().split("\n\n").forEach { builder.append(it); builder.endBlock() }
        return builder.build()
    }
}
