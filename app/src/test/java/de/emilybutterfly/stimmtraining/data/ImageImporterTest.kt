package de.emilybutterfly.stimmtraining.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ImageImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun jpeg(width: Int, height: Int, orientation: Int? = null): File {
        val file = File(context.cacheDir, "source_${width}x$height.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun importAndMeasure(source: File): Pair<Int, Int> {
        val target = File(context.filesDir, "imported.jpg")
        ImageImporter.import(context.contentResolver, Uri.fromFile(source), target)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun rotatesClockwiseAndBackWithoutTouchingTheOriginal() {
        // Links oben ein roter Fleck: nach einer Rechtsdrehung liegt er rechts oben.
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 0 until 60) for (y in 0 until 60) setPixel(x, y, Color.RED)
        }
        val source = File(context.cacheDir, "rotate_source.jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val original = source.readBytes()
        val right = File(context.cacheDir, "rotate_right.jpg")
        ImageImporter.rotate(source, right, clockwise = true)
        val rotated = BitmapFactory.decodeFile(right.path)
        assertEquals(200 to 400, rotated.width to rotated.height)
        assertEquals("rot rechts oben", true, Color.red(rotated.getPixel(170, 30)) > 200 && Color.green(rotated.getPixel(170, 30)) < 80)
        assertEquals("links oben weiß", true, Color.green(rotated.getPixel(30, 30)) > 200)
        assertEquals(true, original.contentEquals(source.readBytes()))

        val back = File(context.cacheDir, "rotate_back.jpg")
        ImageImporter.rotate(right, back, clockwise = false)
        val restored = BitmapFactory.decodeFile(back.path)
        assertEquals(400 to 200, restored.width to restored.height)
        assertEquals(true, Color.red(restored.getPixel(30, 30)) > 200 && Color.green(restored.getPixel(30, 30)) < 80)
    }

    @Test
    fun rotatingSomethingThatIsNoImageFailsCleanly() {
        val source = File(context.cacheDir, "no_image.jpg").apply { writeText("kein Bild") }
        val target = File(context.cacheDir, "no_image_rotated.jpg")
        assertThrows(IOException::class.java) { ImageImporter.rotate(source, target, clockwise = true) }
        assertEquals(false, target.exists())
    }

    @Test
    fun importsAPhotoUnchangedInSize() {
        assertEquals(400 to 300, importAndMeasure(jpeg(400, 300)))
    }

    @Test
    fun appliesExifRotation() {
        assertEquals(300 to 400, importAndMeasure(jpeg(400, 300, ExifInterface.ORIENTATION_ROTATE_90)))
    }

    @Test
    fun shrinksLargePhotos() {
        val (width, height) = importAndMeasure(jpeg(6000, 2000))
        assertEquals(2560, width)
        assertEquals(853, height, 1f)
    }

    @Test
    fun rejectsFilesThatAreNoImages() {
        val text = File(context.cacheDir, "kein-bild.jpg").apply { writeText("hallo") }
        assertThrows(IOException::class.java) { importAndMeasure(text) }
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Float) =
        assertEquals(expected.toFloat(), actual.toFloat(), tolerance)
}
