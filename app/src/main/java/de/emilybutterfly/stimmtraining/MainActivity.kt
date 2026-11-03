package de.emilybutterfly.stimmtraining

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.emilybutterfly.stimmtraining.ui.LocalAppSettings
import de.emilybutterfly.stimmtraining.ui.backup.BackupScreen
import de.emilybutterfly.stimmtraining.ui.detail.DetailScreen
import de.emilybutterfly.stimmtraining.ui.library.LibraryScreen
import de.emilybutterfly.stimmtraining.ui.library.LibraryTextScreen
import de.emilybutterfly.stimmtraining.ui.list.RecordingListScreen
import de.emilybutterfly.stimmtraining.ui.pitch.PitchHistoryScreen
import de.emilybutterfly.stimmtraining.ui.record.RecordScreen
import de.emilybutterfly.stimmtraining.ui.record.RecordViewModel
import de.emilybutterfly.stimmtraining.ui.settings.SettingsScreen
import de.emilybutterfly.stimmtraining.ui.techniques.TechniqueDetailScreen
import de.emilybutterfly.stimmtraining.ui.techniques.TechniquesScreen
import de.emilybutterfly.stimmtraining.ui.theme.StimmtrainingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // Nur bei einem frischen Start: Dann kann kein wiederhergestellter Entwurf mehr
            // auf diese Dateien zeigen (nach dem Beenden durch Android schon).
            val container = (application as StimmtrainingApp).container
            container.appScope.launch(Dispatchers.IO) { container.files.cleanOldDrafts() }
        }
        enableEdgeToEdge()
        val settings = (application as StimmtrainingApp).container.settings
        setContent {
            StimmtrainingTheme {
                CompositionLocalProvider(LocalAppSettings provides settings) {
                    AppNavigation()
                }
            }
        }
    }
}

/**
 * Führt eine Navigation nur aus, solange [entry] die oberste Ansicht ist. So öffnet ein
 * Doppeltipp keine Ansicht doppelt und schließt nicht versehentlich die Startansicht.
 */
private fun NavController.fromCurrent(entry: NavBackStackEntry, action: NavController.() -> Unit) {
    if (currentBackStackEntry?.id == entry.id) action()
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "list") {
        composable("list") { entry ->
            RecordingListScreen(
                onNewRecording = { navController.fromCurrent(entry) { navigate("record") } },
                onOpenRecording = { id -> navController.fromCurrent(entry) { navigate("detail/$id") } },
                onOpenBackup = { navController.fromCurrent(entry) { navigate("backup") } },
                onOpenTechniques = { navController.fromCurrent(entry) { navigate("techniques") } },
                onOpenPitchHistory = { navController.fromCurrent(entry) { navigate("pitch") } },
                onOpenSettings = { navController.fromCurrent(entry) { navigate("settings") } },
                onOpenLibrary = { navController.fromCurrent(entry) { navigate("library") } },
            )
        }
        composable("library") { entry ->
            LibraryScreen(
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onOpenText = { id -> navController.fromCurrent(entry) { navigate("library/text/$id") } },
                onAddText = { navController.fromCurrent(entry) { navigate("library/new") } },
            )
        }
        composable("library/pick") { entry ->
            LibraryScreen(
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onOpenText = {},
                onAddText = { navController.fromCurrent(entry) { navigate("library/new") } },
                onPick = { id ->
                    navController.fromCurrent(entry) {
                        // Das Ergebnis landet im gesicherten Zustand der Aufnahme, die gewählt hat.
                        previousBackStackEntry?.savedStateHandle?.set(RecordViewModel.KEY_PICKED_LIBRARY_TEXT, id)
                        popBackStack()
                    }
                },
            )
        }
        composable("library/new") { entry ->
            LibraryTextScreen(
                textId = null,
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onRecord = {},
            )
        }
        composable(
            route = "library/text/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            LibraryTextScreen(
                textId = entry.arguments?.getLong("id") ?: 0L,
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onRecord = { id -> navController.fromCurrent(entry) { navigate("record?${RecordViewModel.ARG_LIBRARY_TEXT}=$id") } },
            )
        }
        composable("settings") { entry ->
            SettingsScreen(onClose = { navController.fromCurrent(entry) { popBackStack() } })
        }
        composable("pitch") { entry ->
            PitchHistoryScreen(
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onOpenRecording = { id -> navController.fromCurrent(entry) { navigate("detail/$id") } },
            )
        }
        composable(
            route = "record?${RecordViewModel.ARG_TECHNIQUE}={${RecordViewModel.ARG_TECHNIQUE}}" +
                "&${RecordViewModel.ARG_EXERCISE}={${RecordViewModel.ARG_EXERCISE}}" +
                "&${RecordViewModel.ARG_LIBRARY_TEXT}={${RecordViewModel.ARG_LIBRARY_TEXT}}",
            arguments = listOf(
                navArgument(RecordViewModel.ARG_TECHNIQUE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(RecordViewModel.ARG_EXERCISE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(RecordViewModel.ARG_LIBRARY_TEXT) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            RecordScreen(
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onPickFromLibrary = { navController.fromCurrent(entry) { navigate("library/pick") } },
                onSaved = { id ->
                    navController.fromCurrent(entry) {
                        // Nur die Aufnahme ersetzen: Zurück führt dorthin, wo sie gestartet wurde
                        // (Liste oder Übung).
                        navigate("detail/$id") {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                recordingId = entry.arguments?.getLong("id") ?: 0L,
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
            )
        }
        composable("backup") { entry ->
            BackupScreen(onClose = { navController.fromCurrent(entry) { popBackStack() } })
        }
        composable("techniques") { entry ->
            TechniquesScreen(
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onOpenTechnique = { id -> navController.fromCurrent(entry) { navigate("technique/${Uri.encode(id)}") } },
            )
        }
        composable("technique/{id}") { entry ->
            TechniqueDetailScreen(
                techniqueId = entry.arguments?.getString("id").orEmpty(),
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onRecordExercise = { technique, exercise ->
                    navController.fromCurrent(entry) {
                        navigate(
                            "record?${RecordViewModel.ARG_TECHNIQUE}=${Uri.encode(technique)}" +
                                "&${RecordViewModel.ARG_EXERCISE}=${Uri.encode(exercise)}",
                        )
                    }
                },
            )
        }
    }
}
