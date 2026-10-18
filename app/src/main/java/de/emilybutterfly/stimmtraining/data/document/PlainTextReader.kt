package de.emilybutterfly.stimmtraining.data.document

/**
 * Reiner Text: Leerzeilen trennen Absätze, einfache Zeilenumbrüche bleiben erhalten – bei
 * Gedichten und Lesetexten sind sie Teil des Textes.
 */
internal object PlainTextReader {
    fun read(text: String, builder: TextDocumentBuilder) {
        var open = false
        for (line in text.lineSequence()) {
            if (builder.isFull) return
            val content = line.trimEnd()
            if (content.isEmpty()) {
                if (open) builder.endBlock()
                open = false
                continue
            }
            if (open) {
                // Text ohne Leerzeilen (ein Absatz je Zeile) nicht zu einem Riesenabsatz machen.
                if (builder.currentBlockLength > TextDocumentBuilder.LONG_BLOCK_CHARS) builder.endBlock() else builder.lineBreak()
            }
            builder.append(content)
            open = true
        }
        builder.endBlock()
    }
}
