package de.emilybutterfly.stimmtraining

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.emilybutterfly.stimmtraining.ui.backup.BackupScreen
import de.emilybutterfly.stimmtraining.ui.detail.DetailScreen
import de.emilybutterfly.stimmtraining.ui.list.RecordingListScreen
import de.emilybutterfly.stimmtraining.ui.record.RecordScreen
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
        setContent {
            StimmtrainingTheme {
                AppNavigation()
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
            )
        }
        composable("record") { entry ->
            RecordScreen(
                onClose = { navController.fromCurrent(entry) { popBackStack() } },
                onSaved = { id ->
                    navController.fromCurrent(entry) {
                        navigate("detail/$id") {
                            popUpTo("list")
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
    }
}
