package de.emilybutterfly.stimmtraining.analysis

import android.app.Application
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.roundToInt

/**
 * analyzeFile mit Robolectric: ShadowMediaExtractor liefert eine „AAC“-Spur, deren Daten in
 * Wahrheit 16-Bit-PCM sind; ein Fake-Decoder (ShadowMediaCodec) gibt sie unverändert oder
 * umgewandelt aus. So laufen Extractor-/Decoder-Schleife, Formatwechsel, Ende-Markierung und
 * Fortschritt wie auf dem Gerät durch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnalyzeFileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val aac = MediaFormat.MIMETYPE_AUDIO_AAC

    /** Echte Datei (für isFile), deren Spur der ShadowMediaExtractor über den Pfad liefert. */
    private fun recording(name: String, format: MediaFormat, data: ByteArray): File {
        val file = File(context.filesDir, name).apply { writeBytes(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)) }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(file.absolutePath), format, data)
        return file
    }

    private fun pcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort((s * 32767).roundToInt().toShort())
        return buffer.array()
    }

    private fun aacFormat(sampleRate: Int, channels: Int, seconds: Double) =
        MediaFormat.createAudioFormat(aac, sampleRate, channels).apply {
            setLong(MediaFormat.KEY_DURATION, (seconds * 1_000_000).toLong())
        }

    private fun a3(seconds: Double, rate: Int = 44_100) = pcm16(Signals.tone(seconds, rate, 220.0, harmonics = Signals.SAWTOOTH))

    private fun passThroughDecoder() {
        ShadowMediaCodec.addDecoder(aac, ShadowMediaCodec.CodecConfig(4096, 4096) { input, output -> output.put(input) })
    }

    /** Decoder, der 16-Bit-Mono-Eingabe in [encoding] mit [channels] Kanälen ausgibt und das im Ausgabeformat meldet. */
    private fun convertingDecoder(sampleRate: Int, channels: Int, encoding: Int) {
        val bytesPerValue = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 4
        }
        val codec = object : ShadowMediaCodec.CodecConfig.Codec {
            override fun onConfigured(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
            }

            override fun process(input: ByteBuffer, output: ByteBuffer) {
                output.order(ByteOrder.nativeOrder())
                while (input.remaining() >= 2) {
                    val v = input.getShort() / 32768f
                    for (c in 0 until channels) {
                        // Kanäle unterschiedlich laut: der Mittelwert muss gebildet werden
                        val s = v * (1f - 0.4f * c / channels)
                        when (encoding) {
                            AudioFormat.ENCODING_PCM_8BIT -> output.put(((s * 127).roundToInt() + 128).toByte())
                            AudioFormat.ENCODING_PCM_16BIT -> output.putShort((s * 32767).roundToInt().toShort())
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                val i = (s * 8_388_607).roundToInt()
                                output.put(i.toByte()).put((i shr 8).toByte()).put((i shr 16).toByte())
                            }
                            AudioFormat.ENCODING_PCM_32BIT -> output.putInt((s * 2_147_483_000.0).roundToInt())
                            else -> output.putFloat(s)
                        }
                    }
                }
            }
        }
        ShadowMediaCodec.addDecoder(aac, ShadowMediaCodec.CodecConfig(4096, 2048 * channels * bytesPerValue, codec))
    }

    @Test
    fun analyzesADecodedTrackAndReportsProgress() {
        passThroughDecoder()
        val file = recording("a3.m4a", aacFormat(44_100, 1, 2.0), a3(2.0))
        val progress = Collections.synchronizedList(mutableListOf<Float>())

        val result = runBlocking { PitchAnalyzer.analyzeFile(file) { progress += it } }

        assertEquals(57, result.mostFrequent)
        assertEquals(57, result.lowest)
        assertEquals(57, result.highest)
        assertTrue("voicedMs=${result.voicedMs}", result.voicedMs in 1800L..2100L)
        assertEquals(0f, progress.first())
        assertEquals(1f, progress.last())
        assertTrue("Fortschritt nicht monoton: $progress", progress.zipWithNext().all { (a, b) -> a <= b })
        assertTrue("kein Zwischenstand: $progress", progress.any { it in 0.3f..0.7f })
    }

    @Test
    fun usesTheRealOutputFormatOfTheDecoder() {
        // Die Spur meldet 22,05 kHz mono (wie HE-AAC vor SBR); der Decoder liefert 44,1 kHz Stereo als Float.
        // Mit dem Spurformat käme Unsinn heraus.
        convertingDecoder(44_100, 2, AudioFormat.ENCODING_PCM_FLOAT)
        val file = recording("he-aac.m4a", aacFormat(22_050, 1, 2.0), a3(2.0))

        val result = runBlocking { PitchAnalyzer.analyzeFile(file) }

        assertEquals(57, result.mostFrequent)
        assertEquals(57, result.lowest)
        assertEquals(57, result.highest)
    }

    @Test
    fun handlesAllPcmEncodingsAndChannelCounts() {
        val cases = listOf(
            AudioFormat.ENCODING_PCM_16BIT to 2,
            AudioFormat.ENCODING_PCM_16BIT to 6,
            AudioFormat.ENCODING_PCM_8BIT to 1,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 2,
            AudioFormat.ENCODING_PCM_32BIT to 1,
            AudioFormat.ENCODING_PCM_FLOAT to 1,
        )
        for ((encoding, channels) in cases) {
            convertingDecoder(48_000, channels, encoding)
            val file = recording("pcm_${encoding}_$channels.m4a", aacFormat(48_000, channels, 1.5), a3(1.5, 48_000))
            val result = runBlocking { PitchAnalyzer.analyzeFile(file) }
            assertEquals("Kodierung $encoding, $channels Kanäle", 57, result.mostFrequent)
        }
    }

    @Test
    fun silentTrackHasNoNotes() {
        passThroughDecoder()
        val file = recording("still.m4a", aacFormat(44_100, 1, 2.0), ByteArray(44_100 * 4))
        val result = runBlocking { PitchAnalyzer.analyzeFile(file) }
        assertFalse(result.hasVoice)
        assertEquals(0L, result.voicedMs)
    }

    @Test
    fun corruptFileThrowsIOException() {
        // Kein Audio: Der Extractor findet keine Spur (auf dem Gerät wirft schon setDataSource).
        val file = File(context.filesDir, "kaputt.m4a").apply { writeBytes(ByteArray(1000) { (it * 31).toByte() }) }
        val e = assertThrows(IOException::class.java) { runBlocking { PitchAnalyzer.analyzeFile(file) } }
        assertEquals("Datei enthält keine Tonspur", e.message)
    }

    @Test
    fun fileWithoutAudioTrackThrowsIOException() {
        val file = recording("video.mp4", MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240), ByteArray(4096))
        assertThrows(IOException::class.java) { runBlocking { PitchAnalyzer.analyzeFile(file) } }
    }

    @Test
    fun missingFileThrowsIOException() {
        val file = File(context.filesDir, "gibt-es-nicht.m4a")
        assertThrows(IOException::class.java) { runBlocking { PitchAnalyzer.analyzeFile(file) } }
    }

    @Test
    fun decoderErrorThrowsIOException() {
        ShadowMediaCodec.addDecoder(
            aac,
            ShadowMediaCodec.CodecConfig(4096, 4096) { _, _ -> throw IllegalStateException("Decoder kaputt") },
        )
        val file = recording("defekt.m4a", aacFormat(44_100, 1, 1.0), a3(1.0))
        val e = assertThrows(IOException::class.java) { runBlocking { PitchAnalyzer.analyzeFile(file) } }
        // Im Testlauf kopiert kotlinx.coroutines Ausnahmen für bessere Stacktraces; daher die ganze Kette prüfen.
        assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is IllegalStateException })
    }

    @Test
    fun unsupportedPcmEncodingThrowsIOException() {
        ShadowMediaCodec.addDecoder(
            aac,
            ShadowMediaCodec.CodecConfig(
                4096,
                4096,
                object : ShadowMediaCodec.CodecConfig.Codec {
                    override fun onConfigured(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_AC3)
                    }

                    override fun process(input: ByteBuffer, output: ByteBuffer) {
                        output.put(input)
                    }
                },
            ),
        )
        val file = recording("ac3.m4a", aacFormat(44_100, 1, 1.0), a3(1.0))
        assertThrows(IOException::class.java) { runBlocking { PitchAnalyzer.analyzeFile(file) } }
    }

    @Test
    fun cancellationStopsTheAnalysis() {
        passThroughDecoder()
        val file = recording("lang.m4a", aacFormat(44_100, 1, 20.0), a3(20.0))
        val progress = Collections.synchronizedList(mutableListOf<Float>())
        var thrown: Throwable? = null

        runBlocking {
            val job = launch(Dispatchers.Default) {
                val self = coroutineContext.job
                try {
                    PitchAnalyzer.analyzeFile(file) { p ->
                        progress += p
                        if (p >= 0.2f) self.cancel()
                    }
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue("$thrown", thrown is CancellationException)
        assertTrue("lief bis zum Ende: $progress", progress.last() < 0.5f)
    }

    @Test
    fun endOfStreamIsReachedWithEmptyTrack() {
        passThroughDecoder()
        val file = recording("leer.m4a", aacFormat(44_100, 1, 0.0), ByteArray(0))
        val result = runBlocking { PitchAnalyzer.analyzeFile(file) }
        assertFalse(result.hasVoice)
    }
}
