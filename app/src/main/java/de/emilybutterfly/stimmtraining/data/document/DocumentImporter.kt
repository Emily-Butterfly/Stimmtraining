package de.emilybutterfly.stimmtraining.data.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.ui.components.formatBytes
import java.io.File
import java.io.IOException

/** Übernimmt eine vom Nutzer gewählte Datei als Text-Entwurf. */
object DocumentImporter {
    /** Größer ist kaum ein Lesetext; Scans als PDF bleiben deutlich darunter. */
    const val MAX_BYTES = 50L * 1024 * 1024

    /**
     * Kopiert [source] in einen Entwurf, erkennt das Format und prüft, ob sich der Text lesen
     * lässt. Wirft eine [IOException] mit verständlicher Meldung, wenn die Datei zu groß, nicht
     * lesbar oder in einem nicht unterstützten Format ist; es bleibt dann nichts zurück.
     */
    fun import(
        resolver: ContentResolver,
        files: FileStore,
        source: Uri,
        maxBytes: Long = MAX_BYTES,
        isCancelled: () -> Boolean = { false },
    ): TextAttachment.Document {
        val (displayName, size) = queryNameAndSize(resolver, source)
        if (size != null && size > maxBytes) throw tooLarge(maxBytes)
        val space = files.freeBytes() - FileStore.RESERVE_BYTES
        if (size != null && size > space) throw noSpace(space)
        val limit = minOf(maxBytes, space)

        val temp = files.newDraftFile("tmp")
        try {
            val input = resolver.openInputStream(source) ?: throw DocumentReadException("Die Datei konnte nicht geöffnet werden.")
            input.use {
                copyLimited(it, temp, limit, isCancelled) { if (limit < maxBytes) noSpace(space) else tooLarge(maxBytes) }
            }

            val kind = DocumentDetector.detect(temp, displayName, resolver.getType(source))
            // Schon hier lesen, damit eine beschädigte Datei gar nicht erst angehängt wird.
            if (kind != DocumentKind.Pdf) DocumentText.read(temp, kind)
            val target = files.newDraftFile(kind.extension)
            FileStore.move(temp, target)
            return TextAttachment.Document(target, documentName(displayName, kind))
        } finally {
            temp.delete()
        }
    }

    /** Der angezeigte Name: wie die Datei hieß, mit passender Endung. */
    internal fun documentName(displayName: String?, kind: DocumentKind): String {
        val name = displayName?.replace(Regex("\\p{Cntrl}"), "")?.trim()?.take(200)
        if (name.isNullOrBlank()) return "Text.${kind.extension}"
        return if (name.contains('.')) name else "$name.${kind.extension}"
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File)
            return file?.name to file?.length()
        }
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null to null
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                name to size
            } ?: (null to null)
        } catch (_: RuntimeException) {
            null to null
        }
    }

    private fun copyLimited(
        input: java.io.InputStream,
        target: File,
        limit: Long,
        isCancelled: () -> Boolean,
        onExceeded: () -> IOException,
    ) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (isCancelled()) throw DocumentReadException("Abgebrochen.")
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw onExceeded()
                output.write(buffer, 0, read)
            }
        }
    }

    private fun tooLarge(maxBytes: Long) =
        DocumentReadException("Die Datei ist zu groß (höchstens ${formatBytes(maxBytes)}).")

    /** [space] ist, was nach der Reserve für Android und die App noch nutzbar ist. */
    private fun noSpace(space: Long) =
        DocumentReadException("Nicht genug Speicherplatz für diese Datei (nutzbar sind noch ${formatBytes(space.coerceAtLeast(0))}).")
}
