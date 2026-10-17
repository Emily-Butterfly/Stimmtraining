package de.emilybutterfly.stimmtraining.data.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * Ein geöffnetes PDF, dessen Seiten einzeln als Bild gerendert werden. [PdfRenderer] erlaubt
 * immer nur eine offene Seite; alle Zugriffe laufen deshalb über [lock]. [render] blockiert
 * und gehört auf einen Hintergrund-Thread.
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Seitengrößen in Punkt (1/72 Zoll). */
    val pageSizes: List<Size>,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val pageCount: Int get() = pageSizes.size

    /**
     * Die Bildgröße, in der Seite [index] für [widthPx] tatsächlich gerendert wird. Große
     * Zoomstufen ergeben wegen der Obergrenzen oft dieselbe Größe – dann muss nicht neu
     * gerendert werden.
     */
    fun renderSize(index: Int, widthPx: Int): Size {
        val page = pageSizes[index]
        var width = widthPx.coerceIn(1, MAX_WIDTH_PX).toDouble()
        var height = width * page.height / page.width
        // Sehr lange Seiten (z. B. Kassenzettel-Format) nicht beliebig groß werden lassen.
        val pixels = width * height
        if (pixels > MAX_PIXELS) {
            val scale = sqrt(MAX_PIXELS / pixels)
            width *= scale
            height *= scale
        }
        return Size(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
    }

    /** Rendert Seite [index] in etwa [widthPx] Pixel Breite, oder null, wenn schon geschlossen. */
    fun render(index: Int, widthPx: Int): Bitmap? {
        val size = renderSize(index, widthPx)
        val key = "$index@${size.width}"
        cache.get(key)?.let { return it }
        synchronized(lock) {
            if (closed) return null
            cache.get(key)?.let { return it }
            val bitmap = try {
                Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                cache.evictAll()
                return null
            }
            // PDF-Seiten sind durchsichtig; ohne Hintergrund wäre schwarzer Text im Dunkelmodus unlesbar.
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            cache.put(key, bitmap)
            return bitmap
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cache.evictAll()
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        private const val MAX_WIDTH_PX = 2048
        private const val MAX_PIXELS = 4_000_000.0
        private val CACHE_BYTES = (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L shl 20, 64L shl 20).toInt()

        /** Öffnet [file]; blockiert. Wirft eine [IOException] mit verständlicher Meldung. */
        fun open(file: File): PdfPages {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (_: SecurityException) {
                    throw IOException("Das PDF ist mit einem Passwort geschützt und lässt sich hier nicht anzeigen.")
                } catch (_: IOException) {
                    throw IOException("Das PDF ist beschädigt und lässt sich hier nicht anzeigen.")
                }
                try {
                    val sizes = (0 until renderer.pageCount).map { i ->
                        renderer.openPage(i).use { Size(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
                    }
                    if (sizes.isEmpty()) throw IOException("Das PDF enthält keine Seiten.")
                    return PdfPages(descriptor, renderer, sizes)
                } catch (e: Exception) {
                    renderer.close()
                    throw e
                }
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}
