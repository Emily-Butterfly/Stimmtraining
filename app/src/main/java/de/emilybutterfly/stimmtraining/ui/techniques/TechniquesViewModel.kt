package de.emilybutterfly.stimmtraining.ui.techniques

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.data.TechniqueContent
import de.emilybutterfly.stimmtraining.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class TechniquesViewModel(container: AppContainer) : ViewModel() {
    /** null, solange die Inhalte noch geladen werden; folgt dem sanften Modus. */
    val content: StateFlow<TechniqueContent?> = container.settings.gentleMode
        .map { gentle -> withContext(Dispatchers.IO) { container.techniques(gentle) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        val Factory = viewModelFactory {
            initializer { TechniquesViewModel(app().container) }
        }
    }
}
