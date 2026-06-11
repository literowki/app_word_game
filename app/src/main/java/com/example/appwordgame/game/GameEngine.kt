package com.example.appwordgame.game

import kotlin.random.Random

enum class GamePhase { PLAYING, FINISHED }

enum class EndReason { TILES_EXHAUSTED, CONSECUTIVE_SCORELESS_TURNS, RESIGNATION }

data class GameResult(
    val winner: Player?,
    val finalScores: Map<Player, Int>,
    val reason: EndReason
)

sealed class MoveResult {
    data class Success(
        val score: Int,
        val wordsFormed: List<String>,
        val gameResult: GameResult?
    ) : MoveResult()

    data class InvalidPlacement(val reason: String) : MoveResult()
    data class InvalidWords(val invalidWords: List<String>) : MoveResult()
    object WrongPlayer : MoveResult()
    object GameOver : MoveResult()
}

sealed class TurnActionResult {
    object Success : TurnActionResult()
    data class Failure(val reason: String) : TurnActionResult()
}

class GameEngine(
    val dictionary: Dictionary,
    random: Random = Random.Default,
    val playerCount: Int = 2,
) {
    val players: List<Player> = Player.activePlayers(playerCount)
    val bag: TileBag = TileBag.standard(random)
    val board: Board = Board()
    val racks: MutableMap<Player, MutableList<Tile>> = mutableMapOf()
    val scores: MutableMap<Player, Int> = mutableMapOf()
    var currentPlayer: Player = players.first()
    var consecutiveScoelessTurns: Int = 0
    var phase: GamePhase = GamePhase.PLAYING
    var gameResult: GameResult? = null

    init {
        players.forEach { player ->
            racks[player] = bag.draw(7).toMutableList()
            scores[player] = 0
        }
    }

    fun playMove(player: Player, placements: List<Pair<Position, Tile>>): MoveResult {
        if (phase == GamePhase.FINISHED) return MoveResult.GameOver
        if (player != currentPlayer) return MoveResult.WrongPlayer

        val rack = racks.getValue(player).toMutableList()
        for ((_, tile) in placements) {
            val idx = if (tile.isBlank) rack.indexOfFirst { it.isBlank }
                      else rack.indexOfFirst { it.letter == tile.letter && !it.isBlank }
            if (idx == -1) return MoveResult.InvalidPlacement("Tile '${tile.letter}' not found in rack")
            rack.removeAt(idx)
        }

        val placementResult = PlacementValidator.validate(board, placements)
        if (placementResult is PlacementValidator.Result.Invalid) {
            return MoveResult.InvalidPlacement(placementResult.reason)
        }

        val words = WordExtractor.extract(board, placements)
        if (words.isEmpty()) return MoveResult.InvalidPlacement("No valid words formed")

        val invalid = words.map { it.word }.filterNot { dictionary.isValid(it) }
        if (invalid.isNotEmpty()) return MoveResult.InvalidWords(invalid)

        val score = ScoreCalculator.calculate(board, placements, words)

        placements.forEach { (pos, tile) -> board.place(pos, tile) }

        rack.addAll(bag.draw(7 - rack.size))
        racks[player] = rack

        scores[player] = scores.getValue(player) + score
        consecutiveScoelessTurns = 0

        val result = if (rack.isEmpty() && bag.isEmpty) {
            finishGame(EndReason.TILES_EXHAUSTED, playerWentOut = player)
        } else {
            switchPlayer()
            null
        }

        return MoveResult.Success(score, words.map { it.word }, result)
    }

    fun pass(player: Player): TurnActionResult {
        if (phase == GamePhase.FINISHED) return TurnActionResult.Failure("Game is over")
        if (player != currentPlayer) return TurnActionResult.Failure("Not your turn")

        consecutiveScoelessTurns++
        val threshold = 6 * (playerCount - 1)
        if (consecutiveScoelessTurns >= threshold) {
            finishGame(EndReason.CONSECUTIVE_SCORELESS_TURNS, playerWentOut = null)
        } else {
            switchPlayer()
        }
        return TurnActionResult.Success
    }

    fun exchangeTiles(player: Player, tiles: List<Tile>): TurnActionResult {
        if (phase == GamePhase.FINISHED) return TurnActionResult.Failure("Game is over")
        if (player != currentPlayer) return TurnActionResult.Failure("Not your turn")
        if (tiles.isEmpty()) return TurnActionResult.Failure("No tiles selected")
        if (bag.remaining < 7) return TurnActionResult.Failure("Bag must have at least 7 tiles to exchange")

        val rack = racks.getValue(player).toMutableList()
        for (tile in tiles) {
            val idx = if (tile.isBlank) rack.indexOfFirst { it.isBlank }
                      else rack.indexOfFirst { it.letter == tile.letter && !it.isBlank }
            if (idx == -1) return TurnActionResult.Failure("Tile '${tile.letter}' not found in rack")
            rack.removeAt(idx)
        }

        val newTiles = bag.draw(tiles.size)
        bag.returnTiles(tiles)
        rack.addAll(newTiles)
        racks[player] = rack

        consecutiveScoelessTurns++
        val threshold = 6 * (playerCount - 1)
        if (consecutiveScoelessTurns >= threshold) {
            finishGame(EndReason.CONSECUTIVE_SCORELESS_TURNS, playerWentOut = null)
        } else {
            switchPlayer()
        }
        return TurnActionResult.Success
    }

    fun resign(player: Player): GameResult {
        val result = finishGame(EndReason.RESIGNATION, playerWentOut = null, resignee = player)
        return result
    }

    private fun finishGame(
        reason: EndReason,
        playerWentOut: Player?,
        resignee: Player? = null
    ): GameResult {
        phase = GamePhase.FINISHED

        if (reason == EndReason.RESIGNATION && resignee != null) {
            val winner = if (playerCount == 2) {
                if (resignee == players[0]) players[1] else players[0]
            } else {
                players.first { it != resignee }
            }
            val result = GameResult(
                winner = winner,
                finalScores = scores.toMap(),
                reason = reason
            )
            gameResult = result
            return result
        }

        if (playerWentOut != null) {
            val otherPlayers = Player.others(players, playerWentOut)
            val totalTileValue = otherPlayers.sumOf { racks.getValue(it).sumOf { t -> t.points } }
            scores[playerWentOut] = scores.getValue(playerWentOut) + totalTileValue
            otherPlayers.forEach { p ->
                scores[p] = scores.getValue(p) - racks.getValue(p).sumOf { t -> t.points }
            }
        } else {
            players.forEach { p ->
                scores[p] = scores.getValue(p) - racks.getValue(p).sumOf { it.points }
            }
        }

        val sorted = players.sortedByDescending { scores.getValue(it) }
        val winner = if (scores.getValue(sorted[0]) > scores.getValue(sorted[1])) sorted[0] else null

        val result = GameResult(winner = winner, finalScores = scores.toMap(), reason = reason)
        gameResult = result
        return result
    }

    private fun switchPlayer() {
        currentPlayer = Player.cycle(players, currentPlayer)
    }

    fun opponent(player: Player): Player = Player.cycle(players, player)
}
