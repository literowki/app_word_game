package com.example.appwordgame.game

import org.junit.Assert.*
import org.junit.Test

class WordExtractorTest {

    private fun words(board: Board, placements: List<Pair<Position, Tile>>): List<String> =
        WordExtractor.extract(board, placements).map { it.word }

    // ── First move ───────────────────────────────────────────────────────────

    @Test
    fun firstMove_horizontalWord() {
        val board = Board()
        val placements = listOf(
            Position(7, 6) to Tile('K', 2),
            Position(7, 7) to Tile('O', 1),
            Position(7, 8) to Tile('T', 2)
        )
        assertEquals(listOf("KOT"), words(board, placements))
    }

    @Test
    fun firstMove_verticalWord() {
        val board = Board()
        val placements = listOf(
            Position(6, 7) to Tile('K', 2),
            Position(7, 7) to Tile('O', 1),
            Position(8, 7) to Tile('T', 2)
        )
        assertEquals(listOf("KOT"), words(board, placements))
    }

    // ── Cross words ──────────────────────────────────────────────────────────

    @Test
    fun horizontalExtension_formsCrossWord() {
        val board = Board()
        // Existing vertical: DOM at rows 6-8, col 7
        board.place(Position(6, 7), Tile('D', 2))
        board.place(Position(7, 7), Tile('O', 1))
        board.place(Position(8, 7), Tile('M', 2))

        // Add "A" to the left and "A" to the right of O → forms "AOA" horizontally
        val placements = listOf(
            Position(7, 6) to Tile('A', 1),
            Position(7, 8) to Tile('A', 1)
        )
        val result = words(board, placements)
        // Primary word: "AOA" (horizontal), secondary: none (single-letter verticals)
        assertTrue("AOA" in result)
    }

    @Test
    fun singleTilePlacement_formsHorizontalWord() {
        val board = Board()
        board.place(Position(7, 7), Tile('K', 2))
        board.place(Position(7, 8), Tile('O', 1))

        val placements = listOf(Position(7, 9) to Tile('T', 2))
        assertEquals(listOf("KOT"), words(board, placements))
    }

    @Test
    fun singleTilePlacement_formsBothDirections() {
        val board = Board()
        // Horizontal: A at (7,6), (7,7)
        board.place(Position(7, 6), Tile('A', 1))
        board.place(Position(7, 7), Tile('B', 3))
        // Vertical: C at (6,8), D at (8,8)
        board.place(Position(6, 8), Tile('C', 2))
        board.place(Position(8, 8), Tile('D', 2))

        // Placing at (7,8) connects to both
        val placements = listOf(Position(7, 8) to Tile('E', 1))
        val result = words(board, placements)
        assertTrue(result.any { it.contains('E') && (it.contains('A') || it.contains('B')) })
        assertTrue(result.any { it.contains('C') && it.contains('D') })
    }

    @Test
    fun noSingleLetterWordsReturned() {
        val board = Board()
        board.place(Position(7, 7), Tile('A', 1))

        // Place tile at (7,8) — forms "AB" horizontally (length 2) but no vertical word
        val placements = listOf(Position(7, 8) to Tile('B', 3))
        val result = words(board, placements)
        assertTrue(result.all { it.length >= 2 })
    }

    @Test
    fun verticalPlacement_withHorizontalCrossWords() {
        val board = Board()
        // existing word at row 7: K-O-T
        board.place(Position(7, 6), Tile('K', 2))
        board.place(Position(7, 7), Tile('O', 1))
        board.place(Position(7, 8), Tile('T', 2))

        // Place vertically at col 7, rows 8–9 (below the O)
        val placements = listOf(
            Position(8, 7) to Tile('D', 2),
            Position(9, 7) to Tile('A', 1)
        )
        val result = words(board, placements)
        // Primary vertical word starts from existing O: ODA
        assertTrue(result.any { it == "ODA" })
    }
}
