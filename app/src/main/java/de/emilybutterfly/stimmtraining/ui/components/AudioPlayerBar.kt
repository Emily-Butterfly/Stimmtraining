package de.emilybutterfly.stimmtraining.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.io.File

/**
 * Abspielen einer Audiodatei mit Play/Pause und Positionsregler. Vorbereitet wird im Hintergrund;
 * Position und Abspielen überstehen das Drehen. Werden Kopfhörer getrennt, pausiert die Wiedergabe,
 * damit die Aufnahme nicht plötzlich laut aus dem Lautsprecher kommt.
 */
@Composable
fun AudioPlayerBar(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var position by rememberSaveable(file) { mutableIntStateOf(0) }
    var resumeAfterRecreate by rememberSaveable(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var seekTarget by remember(file) { mutableStateOf<Float?>(null) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                duration = it.duration
                if (position in 1 until duration) it.seekTo(position)
                player = it
                if (resumeAfterRecreate) {
                    it.start()
                    playing = true
                }
                resumeAfterRecreate = false
            }
            mp.setOnCompletionListener {
                playing = false
                position = 0
                if (!failed) it.seekTo(0)
            }
            // Ohne eigene Fehlerbehandlung ruft MediaPlayer stattdessen onCompletion auf – und
            // seekTo im Fehlerzustand löst den nächsten Fehler aus, endlos.
            mp.setOnErrorListener { _, _, _ ->
                failed = true
                playing = false
                player = null
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
        onDispose {
            mp.release()
            player = null
            playing = false
        }
    }

    LaunchedEffect(playing, player) {
        val mp = player ?: return@LaunchedEffect
        while (playing) {
            if (seekTarget == null) position = mp.currentPosition
            delay(50)
        }
    }

    // Kopfhörer getrennt (Kabel gezogen, Bluetooth weg): anhalten, statt laut weiterzuspielen.
    DisposableEffect(playing) {
        if (!playing) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                player?.let { if (it.isPlaying) it.pause() }
                playing = false
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Beim Drehen gleich wieder weiterspielen; beim Verlassen der App angehalten lassen.
        resumeAfterRecreate = playing && activity?.isChangingConfigurations == true
        player?.let { if (it.isPlaying) it.pause() }
        playing = false
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = {
                    val mp = player ?: return@FilledIconButton
                    if (playing) {
                        mp.pause()
                        playing = false
                    } else {
                        mp.start()
                        playing = true
                    }
                },
                enabled = player != null && !failed,
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Abspielen",
                )
            }
            Slider(
                value = seekTarget ?: position.toFloat(),
                onValueChange = { seekTarget = it },
                onValueChangeFinished = {
                    seekTarget?.let { target ->
                        player?.seekTo(target.toInt())
                        position = target.toInt()
                    }
                    seekTarget = null
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = player != null,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
        Text(
            text = if (failed) {
                "Audiodatei kann nicht abgespielt werden."
            } else {
                "${formatDuration((seekTarget?.toLong() ?: position.toLong()))} / ${formatDuration(duration.toLong())}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )
    }
}
