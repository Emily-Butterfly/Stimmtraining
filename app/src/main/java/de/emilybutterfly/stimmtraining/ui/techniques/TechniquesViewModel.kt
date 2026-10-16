package de.emilybutterfly.stimmtraining.ui.techniques

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.emilybutterfly.stimmtraining.AppContainer
import de.emilybutterfly.stimmtraining.data.TechniqueContent
import de.emilybutterfly.stimmtraining.ui.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TechniquesViewModel(container: AppContainer) : ViewModel() {
    private val _content = MutableStateFlow<TechniqueContent?>(null)

    /** null, solange die Inhalte noch geladen werden. */
    val content: StateFlow<TechniqueContent?> = _content.asStateFlow()

    init {
        viewModelScope.launch {
            _content.value = withContext(Dispatchers.IO) { container.techniques }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TechniquesViewModel(app().container) }
        }
    }
}
