package com.example.appwordgame.game

object WordExtractor {

    data class WordWithPositions(
        val word: String,
        val positions: List<Position>
    )

    fun extract(board: Board, placements: List<Pair<Position, Tile>>): List<WordWithPositions> {
        val allTiles: Map<Position, Tile> = buildMap {
            putAll(board.allTiles())
            placements.forEach { (pos, tile) -> put(pos, tile) }
        }

        val placedPositions = placements.map { it.first }.toSet()
        val rows = placedPositions.map { it.row }.toSet()
        val cols = placedPositions.map { it.col }.toSet()
        val isHorizontal = rows.size == 1

        val words = mutableListOf<WordWithPositions>()

        if (isHorizontal) {
            // Primary: the horizontal word spanning all placed tiles
            wordInRow(allTiles, rows.first(), cols.min())?.let { words.add(it) }

            // Secondary: vertical cross-words at each placed position
            placedPositions.forEach { pos ->
                wordInCol(allTiles, pos.col, pos.row)?.let { words.add(it) }
            }
        } else {
            // Primary: the vertical word spanning all placed tiles
            wordInCol(allTiles, cols.first(), rows.min())?.let { words.add(it) }

            // Secondary: horizontal cross-words at each placed position
            placedPositions.forEach { pos ->
                wordInRow(allTiles, pos.row, pos.col)?.let { words.add(it) }
            }
        }

        return words
    }

    private fun wordInRow(allTiles: Map<Position, Tile>, row: Int, seedCol: Int): WordWithPositions? {
        var col = seedCol
        while (col > 0 && allTiles.containsKey(Position(row, col - 1))) col--

        val positions = mutableListOf<Position>()
        while (col <= 14 && allTiles.containsKey(Position(row, col))) {
            positions.add(Position(row, col++))
        }

        if (positions.size < 2) return null
        val word = positions.joinToString("") { allTiles.getValue(it).letter.toString() }
        return WordWithPositions(word, positions)
    }

    private fun wordInCol(allTiles: Map<Position, Tile>, col: Int, seedRow: Int): WordWithPositions? {
        var row = seedRow
        while (row > 0 && allTiles.containsKey(Position(row - 1, col))) row--

        val positions = mutableListOf<Position>()
        while (row <= 14 && allTiles.containsKey(Position(row, col))) {
            positions.add(Position(row++, col))
        }

        if (positions.size < 2) return null
        val word = positions.joinToString("") { allTiles.getValue(it).letter.toString() }
        return WordWithPositions(word, positions)
    }
}
