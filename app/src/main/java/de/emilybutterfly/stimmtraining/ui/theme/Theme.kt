package de.emilybutterfly.stimmtraining.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Rosé und Hellblau, angelehnt an die Farben der Trans-Flagge.
private val LightColors = lightColorScheme(
    primary = Color(0xFF9C3D6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD8E5),
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFF00658C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6E7FF),
    onSecondaryContainer = Color(0xFF001E2D),
    tertiary = Color(0xFF7B5266),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E8),
    onTertiaryContainer = Color(0xFF301122),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB0CC),
    onPrimary = Color(0xFF5E1138),
    primaryContainer = Color(0xFF7D2751),
    onPrimaryContainer = Color(0xFFFFD8E5),
    secondary = Color(0xFF82CFFF),
    onSecondary = Color(0xFF00344B),
    secondaryContainer = Color(0xFF004C6B),
    onSecondaryContainer = Color(0xFFC6E7FF),
    tertiary = Color(0xFFEDB8CF),
    onTertiary = Color(0xFF482537),
    tertiaryContainer = Color(0xFF613B4E),
    onTertiaryContainer = Color(0xFFFFD8E8),
    background = Color(0xFF1F1A1C),
    surface = Color(0xFF1F1A1C),
)

@Composable
fun StimmtrainingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
