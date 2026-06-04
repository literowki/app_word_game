package com.example.appwordgame.game

import org.junit.Assert.*
import org.junit.Test

class PlacementValidatorTest {

    private fun valid(board: Board, placements: List<Pair<Position, Tile>>) =
        PlacementValidator.validate(board, placements) is PlacementValidator.Result.Valid

    private fun invalidReason(board: Board, placements: List<Pair<Position, Tile>>): String =
        (PlacementValidator.validate(board, placements) as PlacementValidator.Result.Invalid).reason

    // ── First move ───────────────────────────────────────────────────────────

    @Test
    fun firstMove_validHorizontalThroughCenter() {
        val board = Board()
        val placements = listOf(
            Position(7, 6) to Tile('K', 2),
            Position(7, 7) to Tile('O', 1),
            Position(7, 8) to Tile('T', 2)
        )
        assertTrue(valid(board, placements))
    }

    @Test
    fun firstMove_mustCoverCenter() {
        val board = Board()
        val placements = listOf(
            Position(7, 5) to Tile('K', 2),
            Position(7, 6) to Tile('O', 1)
        )
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("center", ignoreCase = true))
    }

    @Test
    fun firstMove_singleTileRejected() {
        val board = Board()
        val placements = listOf(Position(7, 7) to Tile('A', 1))
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("2", ignoreCase = true))
    }

    @Test
    fun firstMove_emptyPlacementsRejected() {
        val board = Board()
        val reason = invalidReason(board, emptyList())
        assertTrue(reason.isNotBlank())
    }

    // ── Subsequent moves ─────────────────────────────────────────────────────

    @Test
    fun subsequentMove_validAdjacentTile() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))
        val placements = listOf(Position(7, 8) to Tile('B', 3))
        assertTrue(valid(board, placements))
    }

    @Test
    fun subsequentMove_singleTileAdjacentVertically() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))
        val placements = listOf(Position(8, 7) to Tile('B', 3))
        assertTrue(valid(board, placements))
    }

    @Test
    fun subsequentMove_notConnectedRejected() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))
        val placements = listOf(
            Position(3, 3) to Tile('B', 3),
            Position(3, 4) to Tile('C', 2)
        )
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("connect", ignoreCase = true))
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    fun tilesNotInLineRejected() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))
        val placements = listOf(
            Position(7, 8) to Tile('B', 3),
            Position(8, 9) to Tile('C', 2)
        )
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("row or column", ignoreCase = true))
    }

    @Test
    fun gapInPlacementRejected() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))
        // Place at cols 8 and 10, leaving a gap at col 9
        val placements = listOf(
            Position(7, 8) to Tile('B', 3),
            Position(7, 10) to Tile('D', 2)
        )
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("gap", ignoreCase = true))
    }

    @Test
    fun existingTileFillsGap() {
        val board = Board()
        board.place(Position(7, 6), Tile('A', 1))
        board.place(Position(7, 7), Tile('B', 3))
        board.place(Position(7, 8), Tile('C', 2))
        // Place at 7,5 and 7,9; the existing tiles fill the middle
        val placements = listOf(
            Position(7, 5) to Tile('D', 2),
            Position(7, 9) to Tile('E', 1)
        )
        // This is a gap between placements but filled by existing tiles, so valid
        assertTrue(valid(board, placements))
    }

    @Test
    fun occupiedPositionRejected() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))
        val placements = listOf(Position(7, 7) to Tile('B', 3))
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("occupied", ignoreCase = true))
    }

    @Test
    fun outOfBoundsRejected() {
        val board = Board()
        val placements = listOf(Position(15, 7) to Tile('A', 1))
        val reason = invalidReason(board, placements)
        assertTrue(reason.contains("bounds", ignoreCase = true))
    }
}
