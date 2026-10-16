package de.emilybutterfly.stimmtraining.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.io.File

/** Abspielen einer Audiodatei mit Play/Pause und Positionsregler. */
@Composable
fun AudioPlayerBar(file: File, modifier: Modifier = Modifier) {
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableIntStateOf(0) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var seekTarget by remember(file) { mutableStateOf<Float?>(null) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        val mp = MediaPlayer()
        var ready = false
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                playing = false
                position = 0
                it.seekTo(0)
            }
            duration = mp.duration
            player = mp
            ready = true
        } catch (_: Exception) {
            failed = true
        }
        if (!ready) mp.release()
        onDispose {
            if (ready) mp.release()
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

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
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
                enabled = player != null,
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
