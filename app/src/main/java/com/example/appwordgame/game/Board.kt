package com.example.appwordgame.game

enum class PremiumSquare {
    NONE, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD
}

class Board {
    private val cells: MutableMap<Position, Tile> = mutableMapOf()

    fun tileAt(pos: Position): Tile? = cells[pos]
    fun hasTile(pos: Position): Boolean = cells.containsKey(pos)
    fun place(pos: Position, tile: Tile) { cells[pos] = tile }
    fun isEmpty(): Boolean = cells.isEmpty()
    fun allTiles(): Map<Position, Tile> = cells.toMap()
    fun restoreFrom(tiles: Map<Position, Tile>) { cells.putAll(tiles) }

    fun premiumAt(pos: Position): PremiumSquare = PREMIUM_MAP[pos] ?: PremiumSquare.NONE

    companion object {
        val CENTER = Position(7, 7)

        val PREMIUM_MAP: Map<Position, PremiumSquare> = buildMap {
            listOf(
                Position(0, 0), Position(0, 7), Position(0, 14),
                Position(7, 0), Position(7, 14),
                Position(14, 0), Position(14, 7), Position(14, 14)
            ).forEach { put(it, PremiumSquare.TRIPLE_WORD) }

            listOf(
                Position(1, 1), Position(2, 2), Position(3, 3), Position(4, 4),
                Position(1, 13), Position(2, 12), Position(3, 11), Position(4, 10),
                Position(10, 4), Position(11, 3), Position(12, 2), Position(13, 1),
                Position(10, 10), Position(11, 11), Position(12, 12), Position(13, 13),
                Position(7, 7)
            ).forEach { put(it, PremiumSquare.DOUBLE_WORD) }

            listOf(
                Position(1, 5), Position(1, 9),
                Position(5, 1), Position(5, 5), Position(5, 9), Position(5, 13),
                Position(9, 1), Position(9, 5), Position(9, 9), Position(9, 13),
                Position(13, 5), Position(13, 9)
            ).forEach { put(it, PremiumSquare.TRIPLE_LETTER) }

            listOf(
                Position(0, 3), Position(0, 11),
                Position(2, 6), Position(2, 8),
                Position(3, 0), Position(3, 7), Position(3, 14),
                Position(6, 2), Position(6, 6), Position(6, 8), Position(6, 12),
                Position(7, 3), Position(7, 11),
                Position(8, 2), Position(8, 6), Position(8, 8), Position(8, 12),
                Position(11, 0), Position(11, 7), Position(11, 14),
                Position(12, 6), Position(12, 8),
                Position(14, 3), Position(14, 11)
            ).forEach { put(it, PremiumSquare.DOUBLE_LETTER) }
        }
    }
}
