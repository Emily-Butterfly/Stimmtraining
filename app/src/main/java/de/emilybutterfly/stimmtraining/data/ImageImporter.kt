package de.emilybutterfly.stimmtraining.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Übernimmt ein Foto (Kamera oder Galerie) als JPEG: richtig gedreht und auf eine Größe
 * verkleinert, bei der Text noch gut lesbar ist, die Backups aber klein bleiben.
 */
object ImageImporter {
    private const val MAX_SIDE = 2560
    private const val QUALITY = 88

    /** So viele Pixel werden höchstens auf einmal dekodiert (etwa 48 MB); größere Bilder vorher verkleinert. */
    private const val MAX_DECODE_PIXELS = 12_000_000L

    fun import(resolver: ContentResolver, source: Uri, target: File) {
        // Mit inJustDecodeBounds liefert decodeStream immer null; nur die Maße werden gefüllt.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(source) ?: throw IOException("Bild konnte nicht geöffnet werden")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Datei ist kein lesbares Bild")

        var sampleSize = 1
        while (
            max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_SIDE ||
            (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) > MAX_DECODE_PIXELS
        ) {
            sampleSize *= 2
        }
        val decoded = decode(resolver, source, sampleSize)

        val orientation = try {
            resolver.openInputStream(source)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        val longest = max(decoded.width, decoded.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        writeTransformed(decoded, matrix, target)
    }

    /**
     * Dekodiert mit [sampleSize]; reicht der Arbeitsspeicher nicht, einmal kleiner. Ein
     * OutOfMemoryError darf nicht nach außen dringen, er würde die App beenden.
     */
    private fun decode(resolver: ContentResolver, source: Uri, sampleSize: Int): Bitmap {
        for (sample in listOf(sampleSize, sampleSize * 2)) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                return resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw IOException("Bild konnte nicht gelesen werden")
            } catch (_: OutOfMemoryError) {
                // noch einmal mit halber Kantenlänge
            }
        }
        throw IOException("Das Bild ist zu groß für den Arbeitsspeicher")
    }

    /**
     * Dreht ein bereits übernommenes Foto um 90° und speichert es als [target]. Das Original bleibt
     * unverändert, damit bei einem Fehler nichts verloren geht.
     */
    fun rotate(source: File, target: File, clockwise: Boolean) {
        val decoded = try {
            BitmapFactory.decodeFile(source.path)
        } catch (_: OutOfMemoryError) {
            null
        } ?: throw IOException("Bild konnte nicht gelesen werden")
        writeTransformed(decoded, Matrix().apply { postRotate(if (clockwise) 90f else -90f) }, target)
    }

    private fun writeTransformed(decoded: Bitmap, matrix: Matrix, target: File) {
        val result = try {
            if (matrix.isIdentity) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            throw IOException("Zu wenig Arbeitsspeicher für das Bild", e)
        }
        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                if (!result.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                    throw IOException("Bild konnte nicht gespeichert werden")
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            if (result !== decoded) result.recycle()
            decoded.recycle()
        }
    }
}
