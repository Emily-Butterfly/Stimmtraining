package de.emilybutterfly.stimmtraining.ui.techniques

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.data.Exercise

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechniqueDetailScreen(
    techniqueId: String,
    onClose: () -> Unit,
    onRecordExercise: (techniqueId: String, exerciseId: String) -> Unit,
    viewModel: TechniquesViewModel = viewModel(factory = TechniquesViewModel.Factory),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val technique = content?.technique(techniqueId)
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(technique?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        when {
            content == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            technique == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Diese Technik gibt es nicht.")
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(technique.subtitle, style = MaterialTheme.typography.titleMedium)
                        Text(technique.intro, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item(key = "why") { TextSection("Warum es hilft", technique.why) }
                if (technique.howToFind.isNotEmpty()) {
                    item(key = "find") { ListSection("So findest du es", technique.howToFind, numbered = true) }
                }
                if (technique.examples.isNotEmpty()) {
                    item(key = "examples") { ListSection("Beispiele", technique.examples, numbered = false) }
                }
                item(key = "exercises-title") { Heading("Übungen") }
                items(technique.exercises, key = { "exercise-${it.id}" }) { exercise ->
                    ExerciseCard(
                        exercise = exercise,
                        initiallyExpanded = exercise == technique.exercises.first(),
                        onRecord = { onRecordExercise(technique.id, exercise.id) },
                    )
                }
                if (technique.pitfalls.isNotEmpty()) {
                    item(key = "pitfalls") { ListSection("Häufige Fehler", technique.pitfalls, numbered = false) }
                }
                if (technique.safety.isNotEmpty()) {
                    item(key = "safety") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                ListSection("Achte auf deine Stimme", technique.safety, numbered = false)
                            }
                        }
                    }
                }
                item(key = "disclaimer") { Disclaimer() }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun TextSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListSection(title: String, items: List<String>, numbered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        ItemList(items, numbered)
    }
}

@Composable
private fun ItemList(items: List<String>, numbered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row {
                Text(
                    if (numbered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 22.dp),
                )
                Text(item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ExerciseCard(exercise: Exercise, initiallyExpanded: Boolean, onRecord: () -> Unit) {
    var expanded by rememberSaveable(exercise.id) { mutableStateOf(initiallyExpanded) }
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    onClickLabel = if (expanded) "Übung zuklappen" else "Übung aufklappen",
                    role = Role.Button,
                ) { expanded = !expanded },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(exercise.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        exercise.duration,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            Text(exercise.goal, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                ExerciseDetails(exercise)
            }
            Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Mit dieser Übung aufnehmen")
            }
        }
    }
}

/** Schritte, Beispiele und Selbstkontrolle einer Übung; auch beim Aufnehmen angezeigt. */
@Composable
fun ExerciseDetails(exercise: Exercise) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (exercise.steps.isNotEmpty()) ListSection("So geht's", exercise.steps, numbered = true)
        if (exercise.examples.isNotEmpty()) ListSection("Material", exercise.examples, numbered = false)
        if (exercise.checks.isNotEmpty()) ListSection("Woran du merkst, dass es passt", exercise.checks, numbered = false)
    }
}
