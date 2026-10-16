package de.emilybutterfly.stimmtraining.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import de.emilybutterfly.stimmtraining.data.FileStore
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.RecordingRepository
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.components.RatingDisplay
import de.emilybutterfly.stimmtraining.ui.components.formatDay
import de.emilybutterfly.stimmtraining.ui.components.formatDuration
import de.emilybutterfly.stimmtraining.ui.components.formatTime
import de.emilybutterfly.stimmtraining.ui.components.localDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File

class RecordingListViewModel(repository: RecordingRepository, private val files: FileStore) : ViewModel() {
    /** null, solange noch geladen wird. */
    val recordings: StateFlow<List<Recording>?> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun photoFile(recording: Recording): File? = recording.photoFile?.let(files::photo)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = app().container
                RecordingListViewModel(container.repository, container.files)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingListScreen(
    onNewRecording: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTechniques: () -> Unit,
    viewModel: RecordingListViewModel = viewModel(factory = RecordingListViewModel.Factory),
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stimmtraining") },
                actions = {
                    IconButton(onClick = onOpenTechniques) {
                        Icon(Icons.Outlined.School, contentDescription = "Techniken & Übungen")
                    }
                    IconButton(onClick = onOpenBackup) {
                        Icon(Icons.Outlined.Backup, contentDescription = "Backup")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecording,
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                text = { Text("Neue Aufnahme") },
            )
        },
    ) { padding ->
        val list = recordings
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> EmptyState(onOpenTechniques, Modifier.fillMaxSize().padding(padding))
            else -> RecordingList(
                recordings = list,
                photoFile = viewModel::photoFile,
                onOpen = onOpenRecording,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun RecordingList(
    recordings: List<Recording>,
    photoFile: (Recording) -> File?,
    onOpen: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    val layoutDirection = LocalLayoutDirection.current
    val byDay = remember(recordings) { recordings.groupBy { localDate(it.recordedAt) } }
    val totalDuration = remember(recordings) { recordings.sumOf { it.durationMs } }
    val warmedUp = remember(recordings) { recordings.count { it.warmedUp } }

    LazyColumn(
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Text(
                "${recordings.size} Aufnahmen · ${formatDuration(totalDuration)} gesamt · $warmedUp aufgewärmt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        byDay.forEach { (day, entries) ->
            item(key = "day-$day") {
                Text(
                    formatDay(day),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(entries, key = { it.id }) { recording ->
                RecordingCard(recording, photoFile(recording), onClick = { onOpen(recording.id) })
            }
        }
    }
}

@Composable
private fun RecordingCard(recording: Recording, photo: File?, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (photo != null) {
                AsyncImage(
                    model = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    recording.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildList {
                    add("${formatTime(recording.recordedAt)} Uhr")
                    add(formatDuration(recording.durationMs))
                    if (recording.title.isNotBlank() && recording.exercise.isNotBlank()) add(recording.exercise)
                }.joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WarmUpBadge(recording.warmedUp)
                    recording.selfRating?.let { RatingDisplay(it, iconSize = 14.dp) }
                }
            }
        }
    }
}

@Composable
private fun WarmUpBadge(warmedUp: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (warmedUp) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (warmedUp) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (warmedUp) Icon(Icons.Filled.Whatshot, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                if (warmedUp) "aufgewärmt" else "nicht aufgewärmt",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun EmptyState(onOpenTechniques: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text("Noch keine Aufnahmen", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tippe auf „Neue Aufnahme“, fotografiere optional den Text, den du liest, und halte fest, ob du aufgewärmt bist.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenTechniques) {
            Icon(Icons.Outlined.School, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Techniken & Übungen ansehen")
        }
    }
}
