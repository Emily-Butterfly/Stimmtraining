package de.emilybutterfly.stimmtraining.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.emilybutterfly.stimmtraining.data.AppSettings

/** Die App-Einstellungen für alle Ansichten; in Tests ohne App-Container null. */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

/**
 * Ob der sanfte Modus an ist. Dann zeigt keine Ansicht Tonhöhen-Werte (Analyse, Noten, Hz,
 * Verlauf); die Texte kommen in ihrer Fassung ohne Vergleiche und Richtwerte.
 */
@Composable
fun gentleMode(): Boolean {
    val settings = LocalAppSettings.current ?: return false
    return settings.gentleMode.collectAsStateWithLifecycle().value
}
