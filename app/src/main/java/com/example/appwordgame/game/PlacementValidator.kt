package com.example.appwordgame.game

object PlacementValidator {

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun validate(board: Board, placements: List<Pair<Position, Tile>>): Result {
        if (placements.isEmpty()) return Result.Invalid("No tiles placed")

        placements.forEach { (pos, _) ->
            if (!pos.isValid) return Result.Invalid("Position $pos is out of bounds")
        }

        val positions = placements.map { it.first }
        val positionSet = positions.toSet()
        if (positionSet.size != placements.size) return Result.Invalid("Duplicate positions in placement")

        positions.forEach { pos ->
            if (board.hasTile(pos)) return Result.Invalid("Position $pos is already occupied")
        }

        val rows = positionSet.map { it.row }.toSet()
        val cols = positionSet.map { it.col }.toSet()
        val isHorizontal = rows.size == 1
        val isVertical = cols.size == 1

        if (!isHorizontal && !isVertical) {
            return Result.Invalid("All tiles must be placed in the same row or column")
        }

        // Check no gaps in the placement line (existing tiles may fill gaps)
        if (isHorizontal) {
            val row = rows.first()
            val minCol = cols.min()
            val maxCol = cols.max()
            for (col in minCol..maxCol) {
                val pos = Position(row, col)
                if (pos !in positionSet && !board.hasTile(pos)) {
                    return Result.Invalid("Gap in placement at $pos")
                }
            }
        } else {
            val col = cols.first()
            val minRow = rows.min()
            val maxRow = rows.max()
            for (row in minRow..maxRow) {
                val pos = Position(row, col)
                if (pos !in positionSet && !board.hasTile(pos)) {
                    return Result.Invalid("Gap in placement at $pos")
                }
            }
        }

        if (board.isEmpty()) {
            if (Board.CENTER !in positionSet) {
                return Result.Invalid("First move must cover the center square H8")
            }
            if (placements.size < 2) {
                return Result.Invalid("First move must place at least 2 tiles")
            }
        } else {
            val neighbors = listOf(Position::up, Position::down, Position::left, Position::right)
            val isConnected = positionSet.any { pos ->
                neighbors.any { dir ->
                    val neighbor = dir(pos)
                    neighbor.isValid && board.hasTile(neighbor) && neighbor !in positionSet
                }
            }
            if (!isConnected) {
                return Result.Invalid("Placement must connect to at least one existing tile")
            }
        }

        return Result.Valid
    }
}
