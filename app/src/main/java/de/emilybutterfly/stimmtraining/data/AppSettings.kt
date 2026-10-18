package de.emilybutterfly.stimmtraining.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Einstellungen, die für die ganze App gelten. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _gentleMode = MutableStateFlow(prefs.getBoolean(KEY_GENTLE, false))

    /**
     * Sanfter Modus: blendet Tonhöhen-Werte (Analyse, Noten, Hz, Verlauf) und Aussagen darüber
     * aus, wie Stimmen nach Geschlecht eingeordnet werden – für Tage, an denen das nicht guttut.
     */
    val gentleMode: StateFlow<Boolean> = _gentleMode.asStateFlow()

    fun setGentleMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GENTLE, enabled).apply()
        _gentleMode.value = enabled
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_GENTLE = "gentleMode"
    }
}
