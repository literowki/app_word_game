package com.example.appwordgame.ui.lobby

import androidx.lifecycle.ViewModel
import com.example.appwordgame.network.GameSessionState
import com.example.appwordgame.network.PlayerInfo
import com.example.appwordgame.network.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LobbyUiState(
    val localNickname: String = "",
    val hostNickname: String = "",
    val players: List<PlayerInfo> = emptyList(),
    val status: SessionStatus = SessionStatus.IDLE,
    val canStartGame: Boolean = false,
    val error: String? = null,
)

class LobbyViewModel : ViewModel() {

    private val _state = MutableStateFlow(LobbyUiState())
    val state: StateFlow<LobbyUiState> = _state.asStateFlow()

    fun updateFromSession(sessionState: GameSessionState) {
        _state.value = LobbyUiState(
            localNickname = sessionState.localNickname,
            hostNickname = sessionState.hostNickname,
            players = sessionState.players,
            status = sessionState.status,
            canStartGame = sessionState.status == SessionStatus.CONNECTED
                && sessionState.players.any { it.id != sessionState.localNickname && it.isConnected },
            error = sessionState.error,
        )
    }
}
