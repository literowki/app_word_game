package com.example.appwordgame.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appwordgame.WordGameApplication
import com.example.appwordgame.network.GameSessionManager
import com.example.appwordgame.network.MessageType
import com.example.appwordgame.network.NetworkMessage
import com.example.appwordgame.network.SerializedGameState
import com.example.appwordgame.network.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val wordGameApp = application as WordGameApplication

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var engine: GameEngine? = null

    private val pendingPositions = mutableListOf<Position>()
    private val pendingTiles = mutableListOf<Tile>()

    private var initialStateLoaded = false

    private var sessionManager: GameSessionManager? = null
    private var isHost: Boolean = false
    private var localPlayerIndex: Int = 0
    private var isMultiplayer: Boolean = false
    private var playerCount: Int = 2
    private var playerNicknamesMap: Map<Player, String> = emptyMap()
    private var subscribedToMessages = false

    init {
        viewModelScope.launch {
            val dict = wordGameApp.dictionary.filterNotNull().first()
            if (!initialStateLoaded) {
                engine = GameEngine(dict)
                refreshState()
            }
        }
    }

    fun initMultiplayer(
        sessionManager: GameSessionManager,
        isHost: Boolean,
        localPlayerIndex: Int,
        playerNicknamesMap: Map<Player, String>,
        playerCount: Int,
    ) {
        this.sessionManager = sessionManager
        this.isHost = isHost
        this.localPlayerIndex = localPlayerIndex
        this.playerNicknamesMap = playerNicknamesMap
        this.playerCount = playerCount
        this.isMultiplayer = true
        subscribeToMessages()
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

    private fun subscribeToMessages() {
        if (subscribedToMessages) return
        subscribedToMessages = true
        viewModelScope.launch {
            sessionManager?.messages?.collect { msg ->
                when (msg.type) {
                    MessageType.GAME_ACTION -> handleRemoteAction(msg)
                    MessageType.GAME_STATE_UPDATE -> handleStateUpdate(msg)
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            sessionManager?.state?.collect { s ->
                if (s.status == SessionStatus.ERROR || s.status == SessionStatus.DISCONNECTED) {
                    _uiState.update { it.copy(connectionError = s.error ?: "Connection lost") }
                } else if (s.status == SessionStatus.CONNECTED) {
                    _uiState.update { it.copy(connectionError = null) }
                }
            }
        }
    }

    // ── Turn check ─────────────────────────────────────────────────────────

    private fun isLocalPlayersTurn(): Boolean {
        val eng = engine ?: return true
        val activePlayers = Player.activePlayers(eng.playerCount)
        return eng.currentPlayer == activePlayers.getOrNull(localPlayerIndex)
    }

    private fun getRemotePlayerIndex(): Int = if (localPlayerIndex == 0) 1 else 0

    private fun getActivePlayers(): List<Player> = Player.activePlayers(engine?.playerCount ?: playerCount)

    // ── Rack interaction ──────────────────────────────────────────────────────

    fun onRackTileTapped(index: Int) {
        val state = _uiState.value
        if (state.phase != GamePhase.PLAYING || state.dictionaryLoading) return
        if (isMultiplayer && !isLocalPlayersTurn()) return
        val newSelected = if (state.selectedRackIndex == index) null else index
        _uiState.value = state.copy(selectedRackIndex = newSelected, moveError = null)
    }

    // ── Board interaction ─────────────────────────────────────────────────────

    fun onBoardCellTapped(pos: Position) {
        val state = _uiState.value
        if (state.phase != GamePhase.PLAYING || state.dictionaryLoading) return
        if (isMultiplayer && !isLocalPlayersTurn()) return

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

    // ── Submit ────────────────────────────────────────────────────────────────

    fun onSubmit() {
        val eng = engine ?: return
        if (pendingPositions.isEmpty()) return
        if (isMultiplayer && !isLocalPlayersTurn()) return

        val placements = pendingPositions.zip(pendingTiles)

        if (!isMultiplayer || isHost) {
            when (val result = eng.playMove(eng.currentPlayer, placements)) {
                is MoveResult.Success -> {
                    pendingPositions.clear()
                    pendingTiles.clear()
                    if (isHost) {
                        broadcastState(result.wordsFormed, result.score, result.gameResult)
                    } else {
                        refreshState(lastWords = result.wordsFormed, lastScore = result.score, gameResult = result.gameResult)
                    }
                }
                is MoveResult.InvalidPlacement ->
                    _uiState.value = _uiState.value.copy(moveError = result.reason)
                is MoveResult.InvalidWords ->
                    _uiState.value = _uiState.value.copy(
                        moveError = "Nieznane słowa: ${result.invalidWords.joinToString()}"
                    )
                else -> {}
            }
        } else {
            val payload = buildSubmitAction(placements)
            sessionManager?.sendMessage(NetworkMessage(MessageType.GAME_ACTION, "", payload))
            pendingPositions.clear()
            pendingTiles.clear()
            refreshState()
        }
    }

    // ── Pass ──────────────────────────────────────────────────────────────────

    fun onPass() {
        val eng = engine ?: return
        if (isMultiplayer && !isLocalPlayersTurn()) return

        if (!isMultiplayer || isHost) {
            when (val r = eng.pass(eng.currentPlayer)) {
                is TurnActionResult.Success -> {
                    pendingPositions.clear()
                    pendingTiles.clear()
                    if (isHost) broadcastState() else refreshState()
                }
                is TurnActionResult.Failure ->
                    _uiState.value = _uiState.value.copy(moveError = r.reason)
            }
        } else {
            sessionManager?.sendMessage(NetworkMessage(MessageType.GAME_ACTION, "", """{"action":"PASS"}"""))
            pendingPositions.clear()
            pendingTiles.clear()
            refreshState()
        }
    }

    // ── Shuffle ───────────────────────────────────────────────────────────────

    fun onShuffleRack() {
        if (isMultiplayer && !isLocalPlayersTurn()) return
        val eng = engine ?: return
        eng.racks[eng.currentPlayer]?.shuffle()
        refreshState()
    }

    // ── Resign ────────────────────────────────────────────────────────────────

    fun onResign() {
        val eng = engine ?: return
        if (isMultiplayer && !isLocalPlayersTurn()) return

        if (!isMultiplayer || isHost) {
            val result = eng.resign(eng.currentPlayer)
            pendingPositions.clear()
            pendingTiles.clear()
            if (isHost) {
                broadcastState(gameResult = result)
            } else {
                refreshState(gameResult = result)
            }
        } else {
            sessionManager?.sendMessage(NetworkMessage(MessageType.GAME_ACTION, "", """{"action":"RESIGN"}"""))
            pendingPositions.clear()
            pendingTiles.clear()
            refreshState()
        }
    }

    // ── New game ──────────────────────────────────────────────────────────────

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

    // ── Exchange ──────────────────────────────────────────────────────────────

    fun onExchangeOpen() {
        if (isMultiplayer && !isLocalPlayersTurn()) return
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
        if (isMultiplayer && !isLocalPlayersTurn()) return

        val tilesToExchange = state.exchangeSelectedIndices
            .sorted()
            .mapNotNull { state.currentRack.getOrNull(it) }
        if (tilesToExchange.isEmpty()) {
            _uiState.value = state.copy(showExchangeDialog = false)
            return
        }

        if (!isMultiplayer || isHost) {
            when (val r = eng.exchangeTiles(eng.currentPlayer, tilesToExchange)) {
                is TurnActionResult.Success -> {
                    pendingPositions.clear()
                    pendingTiles.clear()
                    if (isHost) broadcastState() else refreshState(clearExchange = true)
                }
                is TurnActionResult.Failure ->
                    _uiState.value = _uiState.value.copy(
                        showExchangeDialog = false,
                        moveError = r.reason
                    )
            }
        } else {
            val tilesJson = buildExchangeAction(tilesToExchange)
            sessionManager?.sendMessage(NetworkMessage(MessageType.GAME_ACTION, "", tilesJson))
            _uiState.value = _uiState.value.copy(showExchangeDialog = false)
            refreshState()
        }
    }

    fun onExchangeCancel() {
        _uiState.value = _uiState.value.copy(showExchangeDialog = false, exchangeSelectedIndices = emptySet())
    }

    // ── Network message handling ──────────────────────────────────────────────

    private fun handleRemoteAction(msg: NetworkMessage) {
        if (!isHost) return
        val eng = engine ?: return
        val payload = msg.payload ?: return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val action = json.optString("action")
        val remotePlayer = getActivePlayers().getOrNull(getRemotePlayerIndex()) ?: return

        when (action) {
            "SUBMIT" -> {
                val placements = parsePlacements(json.optJSONArray("placements")) ?: return
                when (val result = eng.playMove(remotePlayer, placements)) {
                    is MoveResult.Success -> {
                        broadcastState(result.wordsFormed, result.score, result.gameResult)
                    }
                    is MoveResult.InvalidPlacement -> sendError(result.reason)
                    is MoveResult.InvalidWords -> sendError("Nieznane słowa: ${result.invalidWords.joinToString()}")
                    else -> {}
                }
            }
            "PASS" -> {
                eng.pass(remotePlayer)
                broadcastState()
            }
            "EXCHANGE" -> {
                val tiles = parseTiles(json.optJSONArray("tiles")) ?: return
                eng.exchangeTiles(remotePlayer, tiles)
                broadcastState()
            }
            "RESIGN" -> {
                val result = eng.resign(remotePlayer)
                broadcastState(gameResult = result)
            }
        }
    }

    private fun handleStateUpdate(msg: NetworkMessage) {
        if (isHost) return
        val payload = msg.payload ?: return
        val eng = engine ?: return
        try {
            val state = SerializedGameState.fromJson(payload)
            eng.applySnapshot(state)
            pendingPositions.clear()
            pendingTiles.clear()
            refreshState()
        } catch (_: Exception) {}
    }

    private fun broadcastState(
        lastWords: List<String> = emptyList(),
        lastScore: Int = 0,
        gameResult: GameResult? = null,
    ) {
        val eng = engine ?: return
        val serialized = SerializedGameState.fromGameEngine(eng, playerNicknamesMap)
        sessionManager?.sendMessage(NetworkMessage(MessageType.GAME_STATE_UPDATE, "", serialized.toJson()))
        refreshState(lastWords = lastWords, lastScore = lastScore, gameResult = gameResult)
    }

    private fun sendError(message: String) {
        sessionManager?.sendMessage(NetworkMessage(MessageType.ERROR, "", message))
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun buildSubmitAction(placements: List<Pair<Position, Tile>>): String {
        return JSONObject().apply {
            put("action", "SUBMIT")
            put("placements", JSONArray(placements.map { (pos, tile) ->
                JSONObject().apply {
                    put("row", pos.row)
                    put("col", pos.col)
                    put("letter", tile.letter.toString())
                    put("points", tile.points)
                    put("isBlank", tile.isBlank)
                }
            }))
        }.toString()
    }

    private fun buildExchangeAction(tiles: List<Tile>): String {
        return JSONObject().apply {
            put("action", "EXCHANGE")
            put("tiles", JSONArray(tiles.map { tile ->
                JSONObject().apply {
                    put("letter", tile.letter.toString())
                    put("points", tile.points)
                    put("isBlank", tile.isBlank)
                }
            }))
        }.toString()
    }

    private fun parsePlacements(array: JSONArray?): List<Pair<Position, Tile>>? {
        if (array == null) return null
        val result = mutableListOf<Pair<Position, Tile>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val pos = Position(obj.getInt("row"), obj.getInt("col"))
            val letter = obj.getString("letter")
            if (letter.isEmpty()) return null
            val tile = Tile(
                letter = letter[0],
                points = obj.getInt("points"),
                isBlank = obj.optBoolean("isBlank"),
            )
            result.add(pos to tile)
        }
        return result
    }

    private fun parseTiles(array: JSONArray?): List<Tile>? {
        if (array == null) return null
        val result = mutableListOf<Tile>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val letter = obj.getString("letter")
            if (letter.isEmpty()) return null
            result.add(Tile(
                letter = letter[0],
                points = obj.getInt("points"),
                isBlank = obj.optBoolean("isBlank"),
            ))
        }
        return result
    }

    // ── State builder ─────────────────────────────────────────────────────────

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

        val activePlayers = Player.activePlayers(eng.playerCount)
        val localPlayer = activePlayers.getOrNull(localPlayerIndex)
        val isMyTurn = localPlayer != null && eng.currentPlayer == localPlayer

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
            playerNicknames = if (playerNicknamesMap.isNotEmpty()) playerNicknamesMap else prev.playerNicknames,
            localPlayerIndex = localPlayerIndex,
            playerCount = eng.playerCount,
            isMyTurn = isMyTurn,
            connectionError = prev.connectionError,
        )
    }
}
