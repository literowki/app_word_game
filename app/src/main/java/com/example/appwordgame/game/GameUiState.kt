package com.example.appwordgame.game

data class GameUiState(
    val dictionaryLoading: Boolean = true,
    val boardTiles: Map<Position, Tile> = emptyMap(),
    val pendingPlacements: Map<Position, Tile> = emptyMap(),
    val currentRack: List<Tile> = emptyList(),
    val selectedRackIndex: Int? = null,
    val scores: Map<Player, Int> = mapOf(Player.ONE to 0, Player.TWO to 0),
    val currentPlayer: Player = Player.ONE,
    val bagRemaining: Int = 100,
    val showExchangeDialog: Boolean = false,
    val exchangeSelectedIndices: Set<Int> = emptySet(),
    val blankPickerPosition: Position? = null,
    val phase: GamePhase = GamePhase.PLAYING,
    val gameResult: GameResult? = null,
    val moveError: String? = null,
    val lastMoveWords: List<String> = emptyList(),
    val lastMoveScore: Int = 0,
    val playerNicknames: Map<Player, String> = emptyMap(),
    val localPlayerIndex: Int = 0,
    val playerCount: Int = 2,
)
