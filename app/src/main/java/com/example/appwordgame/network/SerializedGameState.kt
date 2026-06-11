package com.example.appwordgame.network

import com.example.appwordgame.game.Board
import com.example.appwordgame.game.Dictionary
import com.example.appwordgame.game.GameEngine
import com.example.appwordgame.game.Player
import com.example.appwordgame.game.Position
import com.example.appwordgame.game.Tile
import com.example.appwordgame.game.TileBag
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SerializedTile(
    val letter: String,
    val points: Int,
    val isBlank: Boolean = false,
) {
    fun toTile(): Tile = Tile(
        letter = if (letter.length == 1) letter[0] else ' ',
        points = points,
        isBlank = isBlank,
    )

    companion object {
        fun fromTile(tile: Tile): SerializedTile = SerializedTile(
            letter = tile.letter.toString(),
            points = tile.points,
            isBlank = tile.isBlank,
        )
    }
}

@Serializable
data class SerializedGameState(
    val playerCount: Int,
    val playerNicknames: List<String>,
    val currentPlayerIndex: Int,
    val scores: List<Int>,
    val racks: List<List<SerializedTile>>,
    val bag: List<SerializedTile>,
    val board: Map<String, SerializedTile>,
) {
    fun toGameEngine(dictionary: Dictionary, random: Random = Random.Default): GameEngine {
        val engine = GameEngine(dictionary, playerCount = playerCount, random = random)
        engine.bag.clear()
        bag.map { it.toTile() }.forEach { tile -> engine.bag.addDirectly(tile) }
        engine.bag.shuffle(random)
        val activePlayers = Player.activePlayers(playerCount)
        activePlayers.forEachIndexed { index, player ->
            engine.racks[player] = racks.getOrNull(index)?.map { it.toTile() }?.toMutableList()
                ?: mutableListOf()
            engine.scores[player] = scores.getOrNull(index) ?: 0
        }
        board.forEach { (key, st) ->
            val parts = key.removeSurrounding("(", ")").split(",")
            if (parts.size == 2) {
                val pos = Position(parts[0].trim().toInt(), parts[1].trim().toInt())
                engine.board.place(pos, st.toTile())
            }
        }
        engine.currentPlayer = activePlayers.getOrNull(currentPlayerIndex) ?: activePlayers.first()
        return engine
    }

    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String): SerializedGameState = Json.decodeFromString(json)

        fun fromGameEngine(engine: GameEngine, nicknames: Map<Player, String>): SerializedGameState {
            val activePlayers = Player.activePlayers(engine.playerCount)
            return SerializedGameState(
                playerCount = engine.playerCount,
                playerNicknames = activePlayers.map { nicknames[it] ?: "Gracz ${it.ordinal + 1}" },
                currentPlayerIndex = activePlayers.indexOf(engine.currentPlayer).coerceAtLeast(0),
                scores = activePlayers.map { engine.scores[it] ?: 0 },
                racks = activePlayers.map { player ->
                    (engine.racks[player] ?: emptyList()).map { SerializedTile.fromTile(it) }
                },
                bag = engine.bag.toList().map { SerializedTile.fromTile(it) },
                board = engine.board.allTiles().mapKeys { (pos, _) -> pos.toString() }
                    .mapValues { (_, tile) -> SerializedTile.fromTile(tile) },
            )
        }
    }
}
