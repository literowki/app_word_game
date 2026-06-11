package com.example.appwordgame.ui.lobby

import androidx.lifecycle.ViewModel
import com.example.appwordgame.network.PlayerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JoinStatus {
    INPUT,
    GENERATING_ANSWER,
    CONNECTING,
    IN_LOBBY,
    ERROR,
}

data class JoinGameUiState(
    val nickname: String = "",
    val invitationString: String = "",
    val answerString: String = "",
    val status: JoinStatus = JoinStatus.INPUT,
    val hostNickname: String = "",
    val players: List<PlayerInfo> = emptyList(),
    val error: String? = null,
    val isLoading: Boolean = false,
)

class JoinGameViewModel : ViewModel() {

    private val _state = MutableStateFlow(JoinGameUiState())
    val state: StateFlow<JoinGameUiState> = _state.asStateFlow()

    fun onInvitationChanged(text: String) {
        _state.value = _state.value.copy(invitationString = text)
    }

    fun onNicknameChanged(text: String) {
        _state.value = _state.value.copy(nickname = text)
    }

    fun onAnswerGenerated(answer: String) {
        _state.value = _state.value.copy(
            answerString = answer,
            status = JoinStatus.CONNECTING,
            isLoading = false,
        )
    }

    fun onLobbyUpdated(hostNickname: String, players: List<PlayerInfo>) {
        _state.value = _state.value.copy(
            status = JoinStatus.IN_LOBBY,
            hostNickname = hostNickname,
            players = players,
            isLoading = false,
        )
    }

    fun onError(error: String) {
        _state.value = _state.value.copy(status = JoinStatus.ERROR, error = error, isLoading = false)
    }

    fun onGenerating() {
        _state.value = _state.value.copy(status = JoinStatus.GENERATING_ANSWER, isLoading = true)
    }
}
