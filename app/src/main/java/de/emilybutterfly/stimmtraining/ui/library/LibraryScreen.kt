package de.emilybutterfly.stimmtraining.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import de.emilybutterfly.stimmtraining.data.TextAttachment
import de.emilybutterfly.stimmtraining.data.library.LibraryRepository
import de.emilybutterfly.stimmtraining.data.library.LibraryText
import de.emilybutterfly.stimmtraining.ui.app
import de.emilybutterfly.stimmtraining.ui.components.documentIcon
import de.emilybutterfly.stimmtraining.ui.components.formatDay
import de.emilybutterfly.stimmtraining.ui.components.localDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Was die Bibliothek gerade zeigt. [texts] ist null, solange noch geladen wird. */
data class LibraryUi(
    val texts: List<LibraryText>? = null,
    val toReadCount: Int = 0,
    val finishedCount: Int = 0,
    /** Alle benutzten Schlagworte, die häufigsten zuerst. */
    val tags: List<String> = emptyList(),
    val query: String = "",
    val tag: String? = null,
    val finished: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val query = savedState.getStateFlow(KEY_QUERY, "")
    private val tag = savedState.getStateFlow<String?>(KEY_TAG, null)
    private val finishedTab = savedState.getStateFlow(KEY_FINISHED, false)

    private val matches = query.flatMapLatest { q ->
        if (q.isBlank()) flowOf(null) else repository.observeMatches(q).map { it.toSet() }
    }

    val ui: StateFlow<LibraryUi> = combine(repository.observeAll(), matches, query, tag, finishedTab) { all, found, q, t, finished ->
        val tags = all.flatMap { it.tagList }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
        val selectedTag = t?.takeIf { it in tags }
        val shown = all
            .filter { it.isFinished == finished }
            .filter { found == null || it.id in found }
            .filter { selectedTag == null || it.tagList.any { tag -> tag.equals(selectedTag, ignoreCase = true) } }
            .let { list -> if (finished) list.sortedByDescending { it.finishedAt } else list }
        LibraryUi(
            texts = shown,
            toReadCount = all.count { !it.isFinished },
            finishedCount = all.count { it.isFinished },
            tags = tags,
            query = q,
            tag = selectedTag,
            finished = finished,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    fun attachment(text: LibraryText): TextAttachment? = repository.attachmentOf(text)

    fun setQuery(value: String) {
        savedState[KEY_QUERY] = value.take(100)
    }

    fun toggleTag(value: String) {
        savedState[KEY_TAG] = if (tag.value.equals(value, ignoreCase = true)) null else value
    }

    fun showFinished(finished: Boolean) {
        savedState[KEY_FINISHED] = finished
    }

    fun togglePinned(text: LibraryText) {
        viewModelScope.launch { repository.setPinned(text.id, !text.pinned) }
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_TAG = "tag"
        private const val KEY_FINISHED = "finished"

        val Factory = viewModelFactory {
            initializer { LibraryViewModel(app().container.library, createSavedStateHandle()) }
        }
    }
}

/**
 * Die Bibliothek: Texte unter „Zu lesen“ und „Gelesen“, mit Suche und Schlagworten. Mit
 * [onPick] wählt man einen Text für eine Aufnahme aus, statt ihn zu öffnen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onClose: () -> Unit,
    onOpenText: (Long) -> Unit,
    onAddText: () -> Unit,
    onPick: ((Long) -> Unit)? = null,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onPick != null) "Text auswählen" else "Bibliothek") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddText,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Text hinzufügen") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = if (ui.finished) 1 else 0) {
                Tab(
                    selected = !ui.finished,
                    onClick = { viewModel.showFinished(false) },
                    text = { Text("Zu lesen (${ui.toReadCount})") },
                )
                Tab(
                    selected = ui.finished,
                    onClick = { viewModel.showFinished(true) },
                    text = { Text("Gelesen (${ui.finishedCount})") },
                )
            }
            if (ui.toReadCount + ui.finishedCount > 0) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Titel oder Text suchen") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Suche leeren")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            if (ui.tags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.tags.forEach { tag ->
                        FilterChip(
                            selected = tag.equals(ui.tag, ignoreCase = true),
                            onClick = { viewModel.toggleTag(tag) },
                            label = { Text(tag) },
                        )
                    }
                }
            }
            val texts = ui.texts
            when {
                texts == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                texts.isEmpty() -> EmptyLibrary(ui, Modifier.fillMaxSize())
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(texts, key = { it.id }) { text ->
                        LibraryTextCard(
                            text = text,
                            attachment = viewModel.attachment(text),
                            onClick = { if (onPick != null) onPick(text.id) else onOpenText(text.id) },
                            onTogglePinned = { viewModel.togglePinned(text) }.takeIf { !text.isFinished },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(ui: LibraryUi, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.LibraryBooks,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        val filtered = ui.query.isNotBlank() || ui.tag != null
        Text(
            when {
                filtered -> "Nichts gefunden"
                ui.finished -> "Noch nichts gelesen"
                else -> "Noch keine Texte"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            when {
                filtered -> "Versuche einen anderen Suchbegriff oder ein anderes Schlagwort."
                ui.finished -> "Texte, die du bis zum Ende gelesen hast, landen hier. Mit „Nochmal lesen“ holst du sie zurück."
                else -> "Lege Texte an, die du lesen möchtest – fotografiert, als Datei oder eingefügt. Beim Aufnehmen wählst du sie " +
                    "mit „Aus Bibliothek“ aus; bei langen Texten merkt sich die App, wie weit du gekommen bist."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LibraryTextCard(
    text: LibraryText,
    attachment: TextAttachment?,
    onClick: () -> Unit,
    onTogglePinned: (() -> Unit)?,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (attachment is TextAttachment.Photo) {
                AsyncImage(
                    model = attachment.pages.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        (attachment as? TextAttachment.Document)?.let { documentIcon(it.kind) } ?: Icons.AutoMirrored.Outlined.LibraryBooks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    progressLabel(text, attachment),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fraction = text.progressFraction
                if (!text.isFinished && fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                }
                if (text.tagList.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        text.tagList.forEach { TagLabel(it) }
                    }
                }
            }
            if (onTogglePinned != null) {
                IconButton(onClick = onTogglePinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = if (text.pinned) "Nicht mehr anheften" else "Anheften",
                        tint = if (text.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TagLabel(tag: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(tag, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** „Neu“, „Etwa 40 % gelesen“ oder „Gelesen am …“. */
fun progressLabel(text: LibraryText, attachment: TextAttachment?): String {
    val kind = when (attachment) {
        is TextAttachment.Photo -> if (attachment.pages.size == 1) "Foto" else "${attachment.pages.size} Seiten"
        is TextAttachment.Document -> attachment.kind.label
        null -> "Datei fehlt"
    }
    val state = when {
        text.finishedAt != null -> "Gelesen " + formatDay(localDate(text.finishedAt)).let { if (it == "Heute" || it == "Gestern") it.lowercase() else "am $it" }
        text.progressFraction != null -> "Etwa ${(text.progressFraction * 100).roundToInt().coerceIn(1, 99)} % gelesen"
        else -> "Noch nicht begonnen"
    }
    return "$kind · $state"
}

/** Ein Hinweis für Screenreader, damit die Fortschrittsleiste nicht stumm bleibt. */
fun Modifier.progressDescription(text: String) = semantics { contentDescription = text }
