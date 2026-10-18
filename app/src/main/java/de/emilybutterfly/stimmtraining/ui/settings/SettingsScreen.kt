package de.emilybutterfly.stimmtraining.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.emilybutterfly.stimmtraining.ui.LocalAppSettings
import de.emilybutterfly.stimmtraining.ui.gentleMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val settings = LocalAppSettings.current
    val gentle = gentleMode()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Die ganze Zeile schaltet um, nicht nur der kleine Schalter.
                        .toggleable(value = gentle, role = Role.Switch, enabled = settings != null) {
                            settings?.setGentleMode(it)
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Sanfter Modus", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Für Tage, an denen Zahlen und Vergleiche dir nicht guttun.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = gentle, onCheckedChange = null)
                }
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ist er an, zeigt die App", style = MaterialTheme.typography.labelLarge)
                    Bullet("keine Tonhöhen-Analyse: keine Noten, keine Hz-Werte, keinen Tonhöhen-Verlauf,")
                    Bullet(
                        "in „Techniken & Übungen“ keine Richtwerte und keine Aussagen darüber, wie Stimmen von " +
                            "anderen eingeordnet werden – die Übungen kommen ohne Messen mit Stimmgerät-Apps aus,",
                    )
                    Bullet("in den Übungen keine Aufnahmen der schweren oder „großen“ Vergleichsstimme,")
                    Bullet("keine Quellenliste zu den Studien.")
                    Text(
                        "Deine Aufnahmen, Texte und bisherigen Analysen bleiben erhalten. Schaltest du den " +
                            "Modus wieder aus, ist alles wieder da.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
