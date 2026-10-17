package de.emilybutterfly.stimmtraining.ui.pitch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.data.Recording
import de.emilybutterfly.stimmtraining.data.RecordingRepository
import de.emilybutterfly.stimmtraining.ui.app
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Eine analysierte Aufnahme im Verlauf. */
data class PitchPoint(
    val id: Long,
    val recordedAt: Long,
    val title: String,
    /** Übung, leer wenn keine angegeben ist. */
    val exercise: String,
    val lowest: Int,
    val mostFrequent: Int,
    val highest: Int,
)

enum class PitchPeriod(val label: String, val days: Int?) {
    Weeks4("4 Wochen", 28),
    Months3("3 Monate", 91),
    Year("1 Jahr", 365),
    All("Alles", null),
}

data class PitchHistory(
    /** Ob es überhaupt analysierte Aufnahmen gibt (unabhängig vom Filter). */
    val anyAnalyzed: Boolean,
    /** Übungen der analysierten Aufnahmen, häufigste zuerst; "" steht für „ohne Übung“. */
    val exercises: List<String>,
    val period: PitchPeriod,
    /** null: alle Übungen. */
    val exercise: String?,
    /** Gefilterte Aufnahmen, älteste zuerst. */
    val points: List<PitchPoint>,
)

class PitchHistoryViewModel(
    repository: RecordingRepository,
    private val savedState: SavedStateHandle,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** null, solange noch geladen wird. */
    val history: StateFlow<PitchHistory?> = combine(
        repository.observeAll(),
        savedState.getStateFlow(KEY_PERIOD, PitchPeriod.All.name),
        savedState.getStateFlow<String?>(KEY_EXERCISE, null),
    ) { recordings, periodName, exercise ->
        val period = PitchPeriod.entries.firstOrNull { it.name == periodName } ?: PitchPeriod.All
        build(recordings, period, exercise)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPeriod(period: PitchPeriod) {
        savedState[KEY_PERIOD] = period.name
    }

    fun setExercise(exercise: String?) {
        savedState[KEY_EXERCISE] = exercise
    }

    private fun build(recordings: List<Recording>, period: PitchPeriod, exercise: String?): PitchHistory {
        val analyzed = recordings.mapNotNull { it.toPoint() }.sortedBy { it.recordedAt }
        val exercises = analyzed.groupingBy { it.exercise }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        // Eine gewählte Übung, die es nicht mehr gibt (z. B. gelöscht), filtert nicht alles weg.
        val activeExercise = exercise?.takeIf { it in exercises }
        val cutoff = period.days?.let { now() - it * DAY_MS }
        val points = analyzed.filter { point ->
            (cutoff == null || point.recordedAt >= cutoff) && (activeExercise == null || point.exercise == activeExercise)
        }
        return PitchHistory(analyzed.isNotEmpty(), exercises, period, activeExercise, points)
    }

    private fun Recording.toPoint(): PitchPoint? {
        val most = pitchMostFrequent ?: return null
        return PitchPoint(
            id = id,
            recordedAt = recordedAt,
            title = displayTitle,
            exercise = exercise.trim(),
            lowest = pitchLowest ?: most,
            mostFrequent = most,
            highest = pitchHighest ?: most,
        )
    }

    companion object {
        private const val KEY_PERIOD = "period"
        private const val KEY_EXERCISE = "exercise"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        val Factory = viewModelFactory {
            initializer { PitchHistoryViewModel(app().container.repository, createSavedStateHandle()) }
        }
    }
}
