package com.example.appwordgame.game

import org.junit.Assert.*
import org.junit.Test

class TileBagTest {

    @Test
    fun standardBagHas100Tiles() {
        val bag = TileBag.standard()
        assertEquals(100, bag.remaining)
    }

    @Test
    fun drawReducesCount() {
        val bag = TileBag.standard()
        val drawn = bag.draw(7)
        assertEquals(7, drawn.size)
        assertEquals(93, bag.remaining)
    }

    @Test
    fun drawLessThanRequestedWhenBagRunsLow() {
        val bag = TileBag.standard()
        bag.draw(98)
        val drawn = bag.draw(7)
        assertEquals(2, drawn.size)
        assertTrue(bag.isEmpty)
    }

    @Test
    fun returnTilesIncreasesCount() {
        val bag = TileBag.standard()
        val drawn = bag.draw(7)
        assertEquals(93, bag.remaining)
        bag.returnTiles(drawn)
        assertEquals(100, bag.remaining)
    }

    @Test
    fun standardBagContainsCorrectTileCount() {
        val bag = TileBag.standard()
        val all = bag.draw(100)
        assertEquals(100, all.size)

        val byLetter = all.groupBy { it.letter }
        assertEquals(9, byLetter['A']?.size)
        assertEquals(1, byLetter['Ą']?.size)
        assertEquals(7, byLetter['E']?.size)
        assertEquals(8, byLetter['I']?.size)
        assertEquals(2, byLetter[' ']?.size)  // blanks
    }

    @Test
    fun blankTilesHaveZeroPoints() {
        val bag = TileBag.standard()
        val all = bag.draw(100)
        val blanks = all.filter { it.isBlank }
        assertEquals(2, blanks.size)
        blanks.forEach { assertEquals(0, it.points) }
    }
}
