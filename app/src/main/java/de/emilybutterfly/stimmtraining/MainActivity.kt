package de.emilybutterfly.stimmtraining

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StimmtrainingTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            RecordingListScreen(
                onNewRecording = { navController.navigate("record") },
                onOpenRecording = { id -> navController.navigate("detail/$id") },
                onOpenBackup = { navController.navigate("backup") },
            )
        }
        composable("record") {
            RecordScreen(
                onClose = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate("detail/$id") {
                        popUpTo("list")
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
                onClose = { navController.popBackStack() },
            )
        }
        composable("backup") {
            BackupScreen(onClose = { navController.popBackStack() })
        }
    }
}
