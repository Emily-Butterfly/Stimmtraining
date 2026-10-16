package de.emilybutterfly.stimmtraining.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GERMAN = Locale.GERMANY
private val dateTimeFormat = DateTimeFormatter.ofPattern("EE, dd.MM.yyyy 'um' HH:mm 'Uhr'", GERMAN)
private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", GERMAN)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", GERMAN)

private fun Long.toLocal() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

fun formatDateTime(epochMillis: Long): String = dateTimeFormat.format(epochMillis.toLocal())

fun formatTime(epochMillis: Long): String = timeFormat.format(epochMillis.toLocal())

fun localDate(epochMillis: Long): LocalDate = epochMillis.toLocal().toLocalDate()

fun formatDay(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        else -> dayFormat.format(date)
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(GERMAN, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(GERMAN, bytes / (1024.0 * 1024))
    else -> "%d KB".format(GERMAN, (bytes + 1023) / 1024)
}

val VOICE_CONDITION_LABELS = listOf("sehr schlecht", "eher schlecht", "mittel", "gut", "sehr gut")
val SELF_RATING_LABELS = listOf("gar nicht zufrieden", "wenig zufrieden", "okay", "zufrieden", "sehr zufrieden")
