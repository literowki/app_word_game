package com.example.appwordgame.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appwordgame.WordGameApplication
import com.example.appwordgame.network.SerializedGameState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val wordGameApp = application as WordGameApplication

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var engine: GameEngine? = null

    private val pendingPositions = mutableListOf<Position>()
    private val pendingTiles = mutableListOf<Tile>()

    private var initialStateLoaded = false

    init {
        viewModelScope.launch {
            val dict = wordGameApp.dictionary.filterNotNull().first()
            if (!initialStateLoaded) {
                engine = GameEngine(dict)
                refreshState()
            }
        }
    }

    fun loadFromSerializedState(
        json: String,
        playerNicknames: Map<Int, String> = emptyMap(),
        localPlayerIndex: Int = 0,
    ) {
        if (initialStateLoaded) return
        initialStateLoaded = true
        viewModelScope.launch {
            val dict = wordGameApp.dictionary.filterNotNull().first()
            val gameState = SerializedGameState.fromJson(json)
            engine = gameState.toGameEngine(dict)
            val activePlayers = Player.activePlayers(gameState.playerCount)
            val nicknameMap = mutableMapOf<Player, String>()
            gameState.playerNicknames.forEachIndexed { index, name ->
                activePlayers.getOrNull(index)?.let { nicknameMap[it] = name }
            }
            _uiState.value = _uiState.value.copy(
                dictionaryLoading = false,
                playerNicknames = nicknameMap,
                localPlayerIndex = localPlayerIndex,
                playerCount = gameState.playerCount,
            )
            refreshState()
        }
    }

    fun onRackTileTapped(index: Int) {
        val state = _uiState.value
        if (state.phase != GamePhase.PLAYING || state.dictionaryLoading) return
        val newSelected = if (state.selectedRackIndex == index) null else index
        _uiState.value = state.copy(selectedRackIndex = newSelected, moveError = null)
    }

    fun onBoardCellTapped(pos: Position) {
        val state = _uiState.value
        if (state.phase != GamePhase.PLAYING || state.dictionaryLoading) return

        val pendingIdx = pendingPositions.indexOf(pos)
        if (pendingIdx != -1) {
            pendingPositions.removeAt(pendingIdx)
            pendingTiles.removeAt(pendingIdx)
            refreshState()
            return
        }

        val selectedIdx = state.selectedRackIndex ?: return
        if (state.boardTiles.containsKey(pos)) return
        val tile = state.currentRack.getOrNull(selectedIdx) ?: return

        if (tile.isBlank && tile.letter == ' ') {
            _uiState.value = state.copy(blankPickerPosition = pos, selectedRackIndex = null)
            return
        }

        pendingPositions.add(pos)
        pendingTiles.add(tile)
        refreshState(clearSelection = true)
    }

    fun onBlankLetterChosen(letter: Char) {
        val pos = _uiState.value.blankPickerPosition ?: return
        pendingPositions.add(pos)
        pendingTiles.add(Tile(letter = letter.uppercaseChar(), points = 0, isBlank = true))
        _uiState.value = _uiState.value.copy(blankPickerPosition = null)
        refreshState(clearSelection = true)
    }

    fun onBlankPickerDismissed() {
        _uiState.value = _uiState.value.copy(
            blankPickerPosition = null,
            selectedRackIndex = null,
            moveError = null
        )
    }

    fun onSubmit() {
        val eng = engine ?: return
        if (pendingPositions.isEmpty()) return
        val placements = pendingPositions.zip(pendingTiles)
        when (val result = eng.playMove(eng.currentPlayer, placements)) {
            is MoveResult.Success -> {
                pendingPositions.clear()
                pendingTiles.clear()
                refreshState(
                    lastWords = result.wordsFormed,
                    lastScore = result.score,
                    gameResult = result.gameResult
                )
            }
            is MoveResult.InvalidPlacement ->
                _uiState.value = _uiState.value.copy(moveError = result.reason)
            is MoveResult.InvalidWords ->
                _uiState.value = _uiState.value.copy(
                    moveError = "Nieznane słowa: ${result.invalidWords.joinToString()}"
                )
            else -> {}
        }
    }

    fun onPass() {
        val eng = engine ?: return
        when (val r = eng.pass(eng.currentPlayer)) {
            is TurnActionResult.Success -> {
                pendingPositions.clear()
                pendingTiles.clear()
                refreshState()
            }
            is TurnActionResult.Failure ->
                _uiState.value = _uiState.value.copy(moveError = r.reason)
        }
    }

    fun onShuffleRack() {
        val eng = engine ?: return
        eng.racks[eng.currentPlayer]?.shuffle()
        refreshState()
    }

    fun onResign() {
        val eng = engine ?: return
        val result = eng.resign(eng.currentPlayer)
        pendingPositions.clear()
        pendingTiles.clear()
        refreshState(gameResult = result)
    }

    fun onNewGame() {
        initialStateLoaded = false
        engine = null
        viewModelScope.launch {
            val dict = wordGameApp.dictionary.filterNotNull().first()
            engine = GameEngine(dict)
            pendingPositions.clear()
            pendingTiles.clear()
            refreshState()
        }
    }

    fun onExchangeOpen() {
        _uiState.value = _uiState.value.copy(
            showExchangeDialog = true,
            exchangeSelectedIndices = emptySet(),
            moveError = null
        )
    }

    fun onExchangeTileToggled(index: Int) {
        val current = _uiState.value.exchangeSelectedIndices
        _uiState.value = _uiState.value.copy(
            exchangeSelectedIndices = if (index in current) current - index else current + index
        )
    }

    fun onExchangeConfirm() {
        val eng = engine ?: return
        val state = _uiState.value
        val tilesToExchange = state.exchangeSelectedIndices
            .sorted()
            .mapNotNull { state.currentRack.getOrNull(it) }
        if (tilesToExchange.isEmpty()) {
            _uiState.value = state.copy(showExchangeDialog = false)
            return
        }
        when (val r = eng.exchangeTiles(eng.currentPlayer, tilesToExchange)) {
            is TurnActionResult.Success -> {
                pendingPositions.clear()
                pendingTiles.clear()
                refreshState(clearExchange = true)
            }
            is TurnActionResult.Failure ->
                _uiState.value = _uiState.value.copy(
                    showExchangeDialog = false,
                    moveError = r.reason
                )
        }
    }

    fun onExchangeCancel() {
        _uiState.value = _uiState.value.copy(showExchangeDialog = false, exchangeSelectedIndices = emptySet())
    }

    private fun refreshState(
        clearSelection: Boolean = false,
        lastWords: List<String> = emptyList(),
        lastScore: Int = 0,
        gameResult: GameResult? = null,
        clearExchange: Boolean = false,
    ) {
        val eng = engine ?: run {
            _uiState.value = GameUiState(dictionaryLoading = true)
            return
        }

        val pendingMap = pendingPositions.zip(pendingTiles).toMap()
        val usedTiles = pendingTiles.toMutableList()
        val fullRack = eng.racks[eng.currentPlayer]?.toMutableList() ?: mutableListOf()
        val effectiveRack = fullRack.toMutableList().also { rack ->
            usedTiles.forEach { used ->
                val idx = if (used.isBlank) rack.indexOfFirst { it.isBlank }
                          else rack.indexOfFirst { it.letter == used.letter && !it.isBlank }
                if (idx != -1) rack.removeAt(idx)
            }
        }

        val prev = _uiState.value
        _uiState.value = GameUiState(
            dictionaryLoading = false,
            boardTiles = eng.board.allTiles(),
            pendingPlacements = pendingMap,
            currentRack = effectiveRack,
            selectedRackIndex = if (clearSelection) null else prev.selectedRackIndex,
            scores = eng.scores.toMap(),
            currentPlayer = eng.currentPlayer,
            bagRemaining = eng.bag.remaining,
            showExchangeDialog = if (clearExchange) false else prev.showExchangeDialog,
            exchangeSelectedIndices = if (clearExchange) emptySet() else prev.exchangeSelectedIndices,
            phase = eng.phase,
            gameResult = gameResult ?: eng.gameResult,
            moveError = null,
            lastMoveWords = lastWords,
            lastMoveScore = lastScore,
            playerNicknames = prev.playerNicknames,
            localPlayerIndex = prev.localPlayerIndex,
            playerCount = eng.playerCount,
        )
    }
}
