package com.example.appwordgame.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.appwordgame.game.GamePhase
import com.example.appwordgame.game.GameViewModel
import com.example.appwordgame.game.Player
import com.example.appwordgame.network.GameSessionManager

@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    sessionManager: GameSessionManager? = null,
    initialState: String? = null,
    playerNicknames: Map<Int, String> = emptyMap(),
    localPlayerIndex: Int = 0,
    isHost: Boolean = false,
    playerCount: Int = 2,
) {
    val vm: GameViewModel = viewModel()
    val state by vm.uiState.collectAsState()

    LaunchedEffect(sessionManager) {
        if (sessionManager != null) {
            val players = sessionManager.state.value.players
            val nicknames = players.mapIndexed { idx, info -> Pair(Player.activePlayers(playerCount).getOrNull(idx), info.nickname) }
                .filter { it.first != null }
                .associate { it.first!! to it.second }
            vm.initMultiplayer(
                sessionManager = sessionManager,
                isHost = isHost,
                localPlayerIndex = localPlayerIndex,
                playerNicknamesMap = nicknames,
                playerCount = playerCount,
            )
        }
    }

    if (initialState != null && state.dictionaryLoading) {
        vm.loadFromSerializedState(initialState, playerNicknames, localPlayerIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF08111E))
    ) {
        if (state.dictionaryLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color(0xFFFFD700))
                Text(
                    text = "Ładowanie słownika…",
                    color = Color(0xFF9CB2CC),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        } else {
            Column(modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                if (state.connectionError != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFB71C1C))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.connectionError ?: "",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }

                ScoreBar(
                    state = state,
                    onResign = vm::onResign,
                    modifier = Modifier.fillMaxWidth()
                )

                BoardComposable(
                    state = state,
                    onCellTapped = vm::onBoardCellTapped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                RackComposable(
                    tiles = state.currentRack,
                    selectedIndex = state.selectedRackIndex,
                    onTileTapped = vm::onRackTileTapped,
                    modifier = Modifier.fillMaxWidth()
                )

                ActionBar(
                    state = state,
                    onSubmit = vm::onSubmit,
                    onPass = vm::onPass,
                    onExchange = vm::onExchangeOpen,
                    onShuffle = vm::onShuffleRack,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.blankPickerPosition != null) {
                BlankLetterDialog(
                    onLetterChosen = vm::onBlankLetterChosen,
                    onDismiss = vm::onBlankPickerDismissed
                )
            }

            if (state.showExchangeDialog) {
                ExchangeDialog(
                    state = state,
                    onToggle = vm::onExchangeTileToggled,
                    onConfirm = vm::onExchangeConfirm,
                    onCancel = vm::onExchangeCancel
                )
            }

            val result = state.gameResult
            if (state.phase == GamePhase.FINISHED && result != null) {
                EndGameOverlay(
                    result = result,
                    playerNicknames = state.playerNicknames,
                    onNewGame = vm::onNewGame,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
        }
    }
}
