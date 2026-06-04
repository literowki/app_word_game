package com.example.appwordgame.game

import org.junit.Assert.*
import org.junit.Test

class ScoreCalculatorTest {

    private fun score(
        board: Board,
        placements: List<Pair<Position, Tile>>,
        words: List<WordExtractor.WordWithPositions>
    ) = ScoreCalculator.calculate(board, placements, words)

    private fun word(vararg positions: Position, word: String) =
        WordExtractor.WordWithPositions(word, positions.toList())

    // ── Basic scoring ─────────────────────────────────────────────────────────

    @Test
    fun simpleWordNoPremium() {
        val board = Board()
        // KOT at row 5, cols 2-4 (no premium squares)
        val placements = listOf(
            Position(5, 2) to Tile('K', 2),
            Position(5, 3) to Tile('O', 1),
            Position(5, 4) to Tile('T', 2)
        )
        val words = listOf(word(Position(5,2), Position(5,3), Position(5,4), word = "KOT"))
        assertEquals(5, score(board, placements, words))  // 2+1+2
    }

    @Test
    fun doubleLetterSquare() {
        val board = Board()
        // Place at (0,3) which is DL — letter value doubled
        val placements = listOf(Position(0, 3) to Tile('Z', 1))
        val words = listOf(word(Position(0,3), word = "Z"))  // length-1 word just for calculation test
        // Z=1, DL → 2; but normally length-1 isn't extracted; this tests the math directly
        assertEquals(2, score(board, placements, words))
    }

    @Test
    fun tripleLetterSquare() {
        val board = Board()
        // (1,5) is TL
        val placements = listOf(Position(1, 5) to Tile('Z', 1))
        val words = listOf(word(Position(1,5), word = "Z"))
        assertEquals(3, score(board, placements, words))
    }

    @Test
    fun doubleWordSquare() {
        val board = Board()
        // (1,1) is DW — word multiplied by 2
        val placements = listOf(
            Position(1, 1) to Tile('K', 2),
            Position(1, 2) to Tile('O', 1)
        )
        val words = listOf(word(Position(1,1), Position(1,2), word = "KO"))
        assertEquals(6, score(board, placements, words))  // (2+1)*2
    }

    @Test
    fun tripleWordSquare() {
        val board = Board()
        // (0,0) is TW
        val placements = listOf(
            Position(0, 0) to Tile('A', 1),
            Position(0, 1) to Tile('B', 3)
        )
        val words = listOf(word(Position(0,0), Position(0,1), word = "AB"))
        assertEquals(12, score(board, placements, words))  // (1+3)*3
    }

    @Test
    fun centerIsDW_firstMove() {
        val board = Board()
        val placements = listOf(
            Position(7, 7) to Tile('A', 1),
            Position(7, 8) to Tile('B', 3)
        )
        val words = listOf(word(Position(7,7), Position(7,8), word = "AB"))
        assertEquals(8, score(board, placements, words))  // (1+3)*2
    }

    @Test
    fun existingTileGetsNoPremium() {
        val board = Board()
        // Tile already at DW square
        board.place(Position(1, 1), Tile('K', 2))
        // New tile placed at plain square extends word
        val placements = listOf(Position(1, 2) to Tile('O', 1))
        val words = listOf(word(Position(1,1), Position(1,2), word = "KO"))
        // K is existing (no premium), O is new at NONE square
        assertEquals(3, score(board, placements, words))
    }

    @Test
    fun binoBonusFor7Tiles() {
        val board = Board()
        val placements = (0..6).map { col -> Position(7, col) to Tile('A', 1) }
        val words = listOf(word(*placements.map { it.first }.toTypedArray(), word = "AAAAAAA"))
        // 7 A's on row 7: col 0=TW, col 3=DL, col 7=DW but col 7 is not in range 0-6
        // Simpler: just check bingo adds 50
        val result = score(board, placements, words)
        assertTrue("Bingo bonus should add 50", result >= 50)
    }

    // ── Multiple words ────────────────────────────────────────────────────────

    @Test
    fun twoWordsScoresSummed() {
        val board = Board()
        val placements = listOf(Position(5, 3) to Tile('A', 1))
        val words = listOf(
            word(Position(5, 2), Position(5, 3), word = "BA"),  // existing B + new A
            word(Position(4, 3), Position(5, 3), word = "CA")   // existing C above + new A
        )
        // Place at (5,3) = NONE premium. Both words use A once each.
        // Word 1: B(existing,1) + A(new,1) = 2
        // Word 2: C(existing,2) + A(new,1) = 3
        board.place(Position(5, 2), Tile('B', 3))
        board.place(Position(4, 3), Tile('C', 2))
        assertEquals(3 + 1 + 2 + 1, score(board, placements, words))  // 7 total... let me recalculate
        // B=3, A=1 → word1=4; C=2, A=1 → word2=3; total=7
    }
}
