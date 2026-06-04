package com.example.appwordgame.game

import kotlin.random.Random

class TileBag(tiles: List<Tile>, private val random: Random = Random.Default) {
    private val _tiles: MutableList<Tile> = tiles.toMutableList()

    val remaining: Int get() = _tiles.size
    val isEmpty: Boolean get() = _tiles.isEmpty()

    fun draw(count: Int): List<Tile> {
        val n = minOf(count, _tiles.size)
        val drawn = _tiles.take(n)
        repeat(n) { _tiles.removeAt(0) }
        return drawn
    }

    fun returnTiles(tiles: List<Tile>) {
        _tiles.addAll(tiles)
        _tiles.shuffle(random)
    }

    companion object {
        // Polish Scrabble (Literaki) official distribution
        fun standard(random: Random = Random.Default): TileBag {
            val tiles = mutableListOf<Tile>().apply {
                addTiles('A', 1, 9)
                addTiles('Ą', 5, 1)
                addTiles('B', 3, 2)
                addTiles('C', 2, 3)
                addTiles('Ć', 6, 1)
                addTiles('D', 2, 3)
                addTiles('E', 1, 7)
                addTiles('Ę', 5, 1)
                addTiles('F', 5, 1)
                addTiles('G', 3, 2)
                addTiles('H', 3, 2)
                addTiles('I', 1, 8)
                addTiles('J', 3, 2)
                addTiles('K', 2, 3)
                addTiles('L', 2, 3)
                addTiles('Ł', 3, 2)
                addTiles('M', 2, 3)
                addTiles('N', 1, 5)
                addTiles('Ń', 7, 1)
                addTiles('O', 1, 6)
                addTiles('Ó', 5, 1)
                addTiles('P', 2, 3)
                addTiles('R', 1, 4)
                addTiles('S', 1, 4)
                addTiles('Ś', 5, 1)
                addTiles('T', 2, 3)
                addTiles('U', 3, 2)
                addTiles('W', 1, 4)
                addTiles('Y', 2, 4)
                addTiles('Z', 1, 5)
                addTiles('Ź', 9, 1)
                addTiles('Ż', 5, 1)
                repeat(2) { add(Tile(' ', 0, isBlank = true)) }
            }
            return TileBag(tiles.shuffled(random), random)
        }

        private fun MutableList<Tile>.addTiles(letter: Char, points: Int, count: Int) {
            repeat(count) { add(Tile(letter, points)) }
        }
    }
}
