package de.emilybutterfly.stimmtraining.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import kotlin.math.log10

/** Nimmt über das Mikrofon in AAC (.m4a, 44,1 kHz, mono) auf. */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    val isActive: Boolean get() = recorder != null

    /**
     * Startet die Aufnahme nach [output]. Erreicht die Datei [maxBytes], beendet Android die
     * Aufnahme selbst (die Datei bleibt abspielbar) und ruft [onLimitReached] auf dem
     * Hauptthread auf; danach muss [stop] aufgerufen werden. So endet eine Aufnahme sauber,
     * bevor der Speicher vollläuft.
     */
    fun start(output: File, maxBytes: Long = 0, onLimitReached: () -> Unit = {}) {
        check(recorder == null) { "Aufnahme läuft bereits" }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(output.absolutePath)
            if (maxBytes > 0) {
                r.setMaxFileSize(maxBytes)
                r.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimitReached()
                }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    fun pause() {
        recorder?.pause()
    }

    fun resume() {
        recorder?.resume()
    }

    /** Aktueller Pegel zwischen 0 und 1 (logarithmisch, -50 dB … 0 dB). */
    fun level(): Float {
        val amplitude = try {
            recorder?.maxAmplitude ?: 0
        } catch (_: IllegalStateException) {
            0
        }
        if (amplitude <= 0) return 0f
        val db = 20 * log10(amplitude / 32767.0)
        return ((db + 50) / 50).toFloat().coerceIn(0f, 1f)
    }

    /** Beendet die Aufnahme. Liefert false, wenn keine brauchbare Datei entstanden ist. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            // Wird geworfen, wenn die Aufnahme zu kurz war und keine Daten enthält.
            false
        } finally {
            r.release()
        }
    }

    companion object {
        /** Dauer einer Audiodatei in Millisekunden, oder null wenn sie nicht lesbar ist. */
        fun durationOf(file: File): Long? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: RuntimeException) {
                null
            } finally {
                retriever.release()
            }
        }
    }
}
