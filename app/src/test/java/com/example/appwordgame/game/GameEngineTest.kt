package com.example.appwordgame.game

import org.junit.Assert.*
import org.junit.Test

class GameEngineTest {

    private val dict = SetDictionary(
        setOf("KOT", "DOM", "ALA", "OKO", "POD", "OD", "DO", "AK", "KA", "LA", "AL", "DA", "AD")
    )

    private fun engine() = GameEngine(dict, kotlin.random.Random(42))

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun initialState_scoresAreZero() {
        val e = engine()
        assertEquals(0, e.scores[Player.ONE])
        assertEquals(0, e.scores[Player.TWO])
    }

    @Test
    fun initialState_eachRackHas7Tiles() {
        val e = engine()
        assertEquals(7, e.racks[Player.ONE]?.size)
        assertEquals(7, e.racks[Player.TWO]?.size)
    }

    @Test
    fun initialState_bagHas86Tiles() {
        val e = engine()
        assertEquals(86, e.bag.remaining)  // 100 - 14 dealt
    }

    @Test
    fun initialState_playerOneGoesFirst() {
        val e = engine()
        assertEquals(Player.ONE, e.currentPlayer)
    }

    // ── Valid moves ───────────────────────────────────────────────────────────

    @Test
    fun validFirstMove_scoresAndSwitchesPlayer() {
        val e = GameEngine(dict)
        // Force rack so we know what tiles are available
        e.racks[Player.ONE] = mutableListOf(
            Tile('K', 2), Tile('O', 1), Tile('T', 2),
            Tile('A', 1), Tile('A', 1), Tile('A', 1), Tile('A', 1)
        )

        val placements = listOf(
            Position(7, 6) to Tile('K', 2),
            Position(7, 7) to Tile('O', 1),
            Position(7, 8) to Tile('T', 2)
        )
        val result = e.playMove(Player.ONE, placements)
        assertTrue(result is MoveResult.Success)
        val success = result as MoveResult.Success
        assertTrue(success.score > 0)
        assertEquals(listOf("KOT"), success.wordsFormed)
        assertEquals(Player.TWO, e.currentPlayer)
    }

    @Test
    fun validMove_rackRefillsTo7() {
        val e = GameEngine(dict)
        e.racks[Player.ONE] = mutableListOf(
            Tile('K', 2), Tile('O', 1), Tile('T', 2),
            Tile('A', 1), Tile('A', 1), Tile('A', 1), Tile('A', 1)
        )
        val placements = listOf(
            Position(7, 6) to Tile('K', 2),
            Position(7, 7) to Tile('O', 1),
            Position(7, 8) to Tile('T', 2)
        )
        e.playMove(Player.ONE, placements)
        assertEquals(7, e.racks[Player.ONE]?.size)
    }

    // ── Wrong player ──────────────────────────────────────────────────────────

    @Test
    fun wrongPlayer_returnsWrongPlayerResult() {
        val e = engine()
        val placements = listOf(
            Position(7, 7) to Tile('A', 1),
            Position(7, 8) to Tile('B', 3)
        )
        val result = e.playMove(Player.TWO, placements)
        assertEquals(MoveResult.WrongPlayer, result)
    }

    // ── Invalid placement ─────────────────────────────────────────────────────

    @Test
    fun firstMoveNotCoveringCenter_isInvalidPlacement() {
        val e = GameEngine(dict)
        e.racks[Player.ONE] = mutableListOf(
            Tile('A', 1), Tile('B', 3), Tile('C', 2),
            Tile('D', 2), Tile('E', 1), Tile('F', 5), Tile('G', 3)
        )
        val placements = listOf(
            Position(5, 5) to Tile('A', 1),
            Position(5, 6) to Tile('B', 3)
        )
        val result = e.playMove(Player.ONE, placements)
        assertTrue(result is MoveResult.InvalidPlacement)
    }

    // ── Invalid words ─────────────────────────────────────────────────────────

    @Test
    fun unknownWord_returnsInvalidWords() {
        val e = GameEngine(dict)
        e.racks[Player.ONE] = mutableListOf(
            Tile('X', 8), Tile('X', 8), Tile('A', 1),
            Tile('A', 1), Tile('A', 1), Tile('A', 1), Tile('A', 1)
        )
        val placements = listOf(
            Position(7, 6) to Tile('X', 8),
            Position(7, 7) to Tile('X', 8)
        )
        val result = e.playMove(Player.ONE, placements)
        assertTrue(result is MoveResult.InvalidWords)
    }

    // ── Pass and end game ─────────────────────────────────────────────────────

    @Test
    fun pass_switchesPlayer() {
        val e = engine()
        e.pass(Player.ONE)
        assertEquals(Player.TWO, e.currentPlayer)
    }

    @Test
    fun sixConsecutivePasses_endsGame() {
        val e = engine()
        repeat(3) {
            e.pass(Player.ONE)
            e.pass(Player.TWO)
        }
        assertEquals(GamePhase.FINISHED, e.phase)
        assertNotNull(e.gameResult)
        assertEquals(EndReason.CONSECUTIVE_SCORELESS_TURNS, e.gameResult?.reason)
    }

    @Test
    fun scoringMove_resetsScoelessTurnCounter() {
        val e = GameEngine(dict)
        e.racks[Player.ONE] = mutableListOf(
            Tile('K', 2), Tile('O', 1), Tile('T', 2),
            Tile('A', 1), Tile('A', 1), Tile('A', 1), Tile('A', 1)
        )
        e.pass(Player.ONE)
        e.pass(Player.TWO)
        assertEquals(2, e.consecutiveScoelessTurns)

        val placements = listOf(
            Position(7, 6) to Tile('K', 2),
            Position(7, 7) to Tile('O', 1),
            Position(7, 8) to Tile('T', 2)
        )
        e.playMove(Player.ONE, placements)
        assertEquals(0, e.consecutiveScoelessTurns)
    }

    // ── Resign ────────────────────────────────────────────────────────────────

    @Test
    fun resign_opposingPlayerWins() {
        val e = engine()
        val result = e.resign(Player.ONE)
        assertEquals(Player.TWO, result.winner)
        assertEquals(GamePhase.FINISHED, e.phase)
    }

    // ── Exchange ──────────────────────────────────────────────────────────────

    @Test
    fun exchange_rackUpdated() {
        val e = engine()
        val before = e.racks[Player.ONE]!!.take(3).toList()
        val result = e.exchangeTiles(Player.ONE, before)
        assertEquals(TurnActionResult.Success, result)
        assertEquals(7, e.racks[Player.ONE]?.size)
        assertEquals(Player.TWO, e.currentPlayer)
    }
}
