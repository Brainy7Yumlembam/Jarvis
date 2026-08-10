package co.aura.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel<State, Event>(initialState: State) : ViewModel() {
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    protected fun updateState(update: (State) -> State) {
        _uiState.value = update(_uiState.value)
    }

    abstract fun onEvent(event: Event)
    
    protected fun launchInScope(block: suspend () -> Unit): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            block()
        }
    }
}
