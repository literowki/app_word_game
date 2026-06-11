package com.example.appwordgame.ui.lobby

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AddPlayerStep {
    GENERATE_INVITATION,
    WAITING_FOR_ANSWER,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class AddPlayerUiState(
    val step: AddPlayerStep = AddPlayerStep.GENERATE_INVITATION,
    val invitationString: String = "",
    val answerString: String = "",
    val error: String? = null,
    val isLoading: Boolean = false,
)

class AddPlayerViewModel : ViewModel() {

    private val _state = MutableStateFlow(AddPlayerUiState())
    val state: StateFlow<AddPlayerUiState> = _state.asStateFlow()

    fun onInvitationGenerated(invitation: String) {
        _state.value = _state.value.copy(
            step = AddPlayerStep.WAITING_FOR_ANSWER,
            invitationString = invitation,
            isLoading = false,
        )
    }

    fun onAnswerSubmitted() {
        _state.value = _state.value.copy(step = AddPlayerStep.CONNECTING, isLoading = true)
    }

    fun onConnected() {
        _state.value = _state.value.copy(step = AddPlayerStep.CONNECTED, isLoading = false)
    }

    fun onError(error: String) {
        _state.value = _state.value.copy(
            step = AddPlayerStep.ERROR,
            error = error,
            isLoading = false,
        )
    }

    fun onReset() {
        _state.value = AddPlayerUiState()
    }
}
