package de.emilybutterfly.stimmtraining.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

/** Empfängt dekodierte Mono-Werte (etwa -1..1); [samples] wird nach dem Aufruf wiederverwendet. */
internal fun interface PcmSink {
    fun onPcm(samples: FloatArray, count: Int, sampleRate: Int)
}

/**
 * Dekodiert die erste Tonspur einer Datei gestreamt zu Mono-PCM (MediaExtractor + MediaCodec).
 * Nichts wird zwischengespeichert: jeder Ausgabepuffer des Decoders geht direkt an den [PcmSink].
 */
internal object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /** Ohne jeden Fortschritt so lange gilt der Decoder als hängen geblieben. */
    private const val STALL_TIMEOUT_MS = 10_000L

    /** Ein Durchlauf wartet höchstens ~10 ms; eine Sekunde Lücke kann nur ein Einfrieren sein. */
    private const val FROZEN_GAP_NS = 1_000_000_000L

    // Erst ab API 31 als Konstanten in AudioFormat vorhanden
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    /**
     * Dekodiert [file] vollständig. Prüft regelmäßig auf Abbruch und meldet den Fortschritt (0..1)
     * nach Abspielposition und Länge der Spur. Wirft eine IOException mit deutscher Meldung, wenn
     * die Datei nicht gelesen oder dekodiert werden kann. Decoder und Extractor werden immer
     * freigegeben, auch bei Fehlern und Abbruch.
     */
    suspend fun decode(file: File, onProgress: (Float) -> Unit, sink: PcmSink) {
        if (!file.isFile) throw IOException("Audiodatei nicht gefunden")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                throw IOException("Audiodatei konnte nicht gelesen werden", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Datei enthält keine Tonspur")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(track)

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("Audioformat wird nicht unterstützt ($mime)", e)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            run(extractor, codec, format, onProgress, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // MediaCodec.CodecException, IllegalStateException usw. aus dem Decoder
            throw IOException("Audiodatei konnte nicht dekodiert werden", e)
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: RuntimeException) {
                }
                try {
                    it.release()
                } catch (_: RuntimeException) {
                }
            }
            try {
                extractor.release()
            } catch (_: RuntimeException) {
            }
        }
    }

    private suspend fun run(
        extractor: MediaExtractor,
        codec: MediaCodec,
        trackFormat: MediaFormat,
        onProgress: (Float) -> Unit,
        sink: PcmSink,
    ) {
        val durationUs = trackFormat.longOr(MediaFormat.KEY_DURATION, 0L)
        var output = OutputFormat(
            sampleRate = trackFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0),
            channels = trackFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1),
            encoding = AudioFormat.ENCODING_PCM_16BIT,
        )
        val converter = PcmConverter()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleTimeUs = 0L
        var decodedUs = 0.0
        var reported = 0f
        var lastActivity = System.nanoTime()
        var lastLoop = lastActivity
        onProgress(0f)

        while (!outputDone) {
            currentCoroutineContext().ensureActive()
            var active = false

            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: throw IOException("Decoder liefert keinen Eingabepuffer")
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val time = extractor.sampleTime
                        codec.queueInputBuffer(index, 0, size, max(time, 0L), 0)
                        sampleTimeUs = max(sampleTimeUs, time)
                        extractor.advance()
                    }
                    active = true
                }
            }

            // Alles abholen, was der Decoder gerade fertig hat; nach dem Eingabeende darauf warten.
            while (!outputDone) {
                val index = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Echte Abtastrate, Kanalzahl und Kodierung; zurück in die äußere Schleife, damit
                    // auch ein Decoder, der nur noch Formatwechsel meldet, Abbruch und Zeitlimit erreicht.
                    output = OutputFormat.of(codec.outputFormat, output)
                    break
                }
                if (index < 0) break // INFO_TRY_AGAIN_LATER oder veraltetes INFO_OUTPUT_BUFFERS_CHANGED

                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (info.size > 0 && !isConfig) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        output.check()
                        buffer.limit(info.offset + info.size)
                        buffer.position(info.offset)
                        val frames = converter.convert(buffer, output.encoding, output.channels)
                        if (frames > 0) {
                            sink.onPcm(converter.mono, frames, output.sampleRate)
                            decodedUs += frames * 1_000_000.0 / output.sampleRate
                        }
                    }
                    active = true
                }
                codec.releaseOutputBuffer(index, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                    active = true
                }
            }

            if (durationUs > 0) {
                val progress = (max(sampleTimeUs.toDouble(), decodedUs) / durationUs).toFloat().coerceIn(0f, 1f)
                if (progress >= reported + 0.01f) {
                    reported = progress
                    onProgress(progress)
                }
            }
            val now = System.nanoTime()
            // Eine lange Pause zwischen zwei Durchläufen heißt: Android hat die App im
            // Hintergrund eingefroren – das ist kein hängender Decoder, also neu zählen.
            if (now - lastLoop > FROZEN_GAP_NS) lastActivity = now
            lastLoop = now
            if (active) {
                lastActivity = now
            } else if ((now - lastActivity) / 1_000_000 > STALL_TIMEOUT_MS) {
                throw IOException("Der Audio-Decoder reagiert nicht mehr")
            }
        }
    }

    private data class OutputFormat(val sampleRate: Int, val channels: Int, val encoding: Int) {
        fun check() {
            if (sampleRate <= 0 || channels <= 0) throw IOException("Audioformat ist unvollständig")
            if (encoding !in SUPPORTED_ENCODINGS) throw IOException("PCM-Format $encoding wird nicht unterstützt")
        }

        companion object {
            /** Echte Werte des Decoders; fehlende Angaben bleiben wie bisher. */
            fun of(format: MediaFormat, previous: OutputFormat) = OutputFormat(
                sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, previous.sampleRate),
                channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, previous.channels),
                encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT),
            )
        }
    }

    private val SUPPORTED_ENCODINGS = setOf(
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        AudioFormat.ENCODING_PCM_8BIT,
        ENCODING_PCM_24BIT_PACKED,
        ENCODING_PCM_32BIT,
    )

    private fun MediaFormat.intOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.longOr(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default

    /** Wandelt einen Ausgabepuffer (verschränkte Kanäle, native Byte-Reihenfolge) in Mono-Float um. */
    private class PcmConverter {
        var mono = FloatArray(4096)
            private set
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)
        private var ints = IntArray(0)
        private var bytes = ByteArray(0)

        fun convert(buffer: ByteBuffer, encoding: Int, channels: Int): Int {
            buffer.order(ByteOrder.nativeOrder())
            return when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val view = buffer.asShortBuffer()
                    val n = view.remaining()
                    if (shorts.size < n) shorts = ShortArray(n)
                    view.get(shorts, 0, n)
                    downmix(n, channels, 1f / 32768f) { shorts[it].toFloat() }
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val view = buffer.asFloatBuffer()
                    val n = view.remaining()
                    if (floats.size < n) floats = FloatArray(n)
                    view.get(floats, 0, n)
                    downmix(n, channels, 1f) { floats[it] }
                }
                ENCODING_PCM_32BIT -> {
                    val view = buffer.asIntBuffer()
                    val n = view.remaining()
                    if (ints.size < n) ints = IntArray(n)
                    view.get(ints, 0, n)
                    downmix(n, channels, 1f / 2147483648f) { ints[it].toFloat() }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    val n = readBytes(buffer)
                    downmix(n, channels, 1f / 128f) { ((bytes[it].toInt() and 0xFF) - 128).toFloat() }
                }
                ENCODING_PCM_24BIT_PACKED -> {
                    val n = readBytes(buffer) / 3
                    downmix(n, channels, 1f / 8388608f) {
                        val b = it * 3
                        // Little Endian, Vorzeichen steckt im höchsten Byte
                        ((bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8) or (bytes[b + 2].toInt() shl 16)).toFloat()
                    }
                }
                else -> throw IOException("PCM-Format $encoding wird nicht unterstützt")
            }
        }

        private fun readBytes(buffer: ByteBuffer): Int {
            val n = buffer.remaining()
            if (bytes.size < n) bytes = ByteArray(n)
            buffer.get(bytes, 0, n)
            return n
        }

        /** Mittelt die Kanäle je Frame; [sample] liefert den Rohwert des i-ten Werts. */
        private inline fun downmix(values: Int, channels: Int, scale: Float, sample: (Int) -> Float): Int {
            val frames = values / channels
            if (mono.size < frames) mono = FloatArray(frames)
            val factor = scale / channels
            var v = 0
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += sample(v++)
                mono[f] = sum * factor
            }
            return frames
        }
    }
}
