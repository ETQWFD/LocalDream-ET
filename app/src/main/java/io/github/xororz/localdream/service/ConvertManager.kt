package io.github.xororz.localdream.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * et.28: application-level state holder for the on-device model conversion.
 * The conversion itself runs inside [ConvertService]'s own service scope;
 * the UI only observes this state and never owns the coroutine. This prevents
 * the conversion from being cancelled when the user leaves the model list or
 * the Activity is reclaimed by the OS.
 */
object ConvertManager {
    sealed class UiState {
        object Idle : UiState()
        object Preparing : UiState()
        data class Running(val stage: String, val percent: Int) : UiState()
        object Finalizing : UiState()
        object Success : UiState()
        data class Failed(val reason: String, val logPath: String?) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun idle() { _state.value = UiState.Idle }
    fun preparing() { _state.value = UiState.Preparing }
    fun running(stage: String, percent: Int) { _state.value = UiState.Running(stage, percent) }
    fun finalizing() { _state.value = UiState.Finalizing }
    fun success() { _state.value = UiState.Success }
    fun failed(reason: String, logPath: String? = null) { _state.value = UiState.Failed(reason, logPath) }
}
