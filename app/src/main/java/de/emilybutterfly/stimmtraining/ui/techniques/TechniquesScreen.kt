package de.emilybutterfly.stimmtraining.ui.techniques

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.emilybutterfly.stimmtraining.data.Source
import de.emilybutterfly.stimmtraining.data.Technique
import de.emilybutterfly.stimmtraining.ui.gentleMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechniquesScreen(
    onClose: () -> Unit,
    onOpenTechnique: (String) -> Unit,
    viewModel: TechniquesViewModel = viewModel(factory = TechniquesViewModel.Factory),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val layoutDirection = LocalLayoutDirection.current
    val gentle = gentleMode()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Techniken & Übungen") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        val loaded = content
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    if (gentle) {
                        "Bausteine für deine Stimme – mit Erklärungen, Beispielen und Übungen. " +
                            "Jede Übung kannst du direkt aufnehmen."
                    } else {
                        "Bausteine für eine feminine Stimme – mit Erklärungen, Beispielen und Übungen. " +
                            "Jede Übung kannst du direkt aufnehmen und so deine Fortschritte vergleichen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item(key = "disclaimer") { Disclaimer() }
            items(loaded.techniques, key = { it.id }) { technique ->
                TechniqueCard(technique, onClick = { onOpenTechnique(technique.id) })
            }
            if (loaded.sources.isNotEmpty()) {
                item(key = "sources") { Sources(loaded.sources) }
            }
        }
    }
}

@Composable
fun Disclaimer(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            "Diese Übungen ersetzen keine Logopädie. Übe ohne Druck und in kurzen Einheiten. " +
                "Bei Schmerzen oder Heiserkeit hörst du auf; hält Heiserkeit länger als zwei Wochen an, " +
                "lass dich ärztlich (HNO/Phoniatrie) untersuchen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun TechniqueCard(technique: Technique, onClick: () -> Unit) {
    val colors = if (technique.focus) {
        CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.elevatedCardColors()
    }
    ElevatedCard(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (technique.focus) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            "Schwerpunkt",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(technique.title, style = MaterialTheme.typography.titleMedium)
                Text(technique.subtitle, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (technique.exercises.size == 1) "1 Übung" else "${technique.exercises.size} Übungen",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun Sources(sources: List<Source>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Quellen ausblenden" else "Quellen anzeigen (${sources.size})")
        }
        if (expanded) {
            sources.forEach { source ->
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(source.url) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(source.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
