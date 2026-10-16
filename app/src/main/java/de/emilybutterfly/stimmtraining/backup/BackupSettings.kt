package de.emilybutterfly.stimmtraining.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class AutoBackupInterval(val days: Long, val label: String) {
    OFF(0, "Aus"),
    DAILY(1, "Täglich"),
    WEEKLY(7, "Wöchentlich"),
}

data class AutoBackupState(
    val folderUri: Uri?,
    val interval: AutoBackupInterval,
    val lastSuccessAt: Long?,
    val lastCount: Int,
    val lastErrorAt: Long?,
    val lastError: String?,
)

/** Einstellungen und Status des automatischen Backups. */
class BackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun current() = AutoBackupState(
        folderUri = prefs.getString(KEY_FOLDER, null)?.toUri(),
        interval = prefs.getString(KEY_INTERVAL, null)
            ?.let { name -> AutoBackupInterval.entries.firstOrNull { it.name == name } }
            ?: AutoBackupInterval.OFF,
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0 },
        lastCount = prefs.getInt(KEY_LAST_COUNT, 0),
        lastErrorAt = prefs.getLong(KEY_LAST_ERROR_AT, 0L).takeIf { it > 0 },
        lastError = prefs.getString(KEY_LAST_ERROR, null),
    )

    val state: Flow<AutoBackupState> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current()) }
        trySend(current())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun setFolder(uri: Uri?) = prefs.edit {
        putString(KEY_FOLDER, uri?.toString())
        remove(KEY_FINGERPRINT)
        remove(KEY_LAST_ERROR)
        remove(KEY_LAST_ERROR_AT)
    }

    fun setInterval(interval: AutoBackupInterval) = prefs.edit { putString(KEY_INTERVAL, interval.name) }

    /** Stand der Daten bei der letzten erfolgreichen Sicherung. */
    val lastFingerprint: String? get() = prefs.getString(KEY_FINGERPRINT, null)

    fun recordSuccess(at: Long, count: Int, fingerprint: String) = prefs.edit {
        putLong(KEY_LAST_SUCCESS, at)
        putInt(KEY_LAST_COUNT, count)
        putString(KEY_FINGERPRINT, fingerprint)
        remove(KEY_LAST_ERROR)
        remove(KEY_LAST_ERROR_AT)
    }

    fun recordFailure(at: Long, message: String) = prefs.edit {
        putLong(KEY_LAST_ERROR_AT, at)
        putString(KEY_LAST_ERROR, message)
    }

    private companion object {
        const val KEY_FOLDER = "folder"
        const val KEY_INTERVAL = "interval"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_COUNT = "last_count"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_ERROR_AT = "last_error_at"
    }
}
