package com.example.appwordgame.game

object ScoreCalculator {

    // board is the state BEFORE placements are applied so premium squares are correctly detected
    fun calculate(
        board: Board,
        placements: List<Pair<Position, Tile>>,
        words: List<WordExtractor.WordWithPositions>
    ): Int {
        val newPositions = placements.associate { (pos, tile) -> pos to tile }
        var total = 0

        for (wordWithPos in words) {
            var letterSum = 0
            var wordMultiplier = 1

            for (pos in wordWithPos.positions) {
                val tile = newPositions[pos] ?: board.tileAt(pos)!!
                val premium = if (pos in newPositions) board.premiumAt(pos) else PremiumSquare.NONE

                letterSum += when (premium) {
                    PremiumSquare.DOUBLE_LETTER -> tile.points * 2
                    PremiumSquare.TRIPLE_LETTER -> tile.points * 3
                    else -> tile.points
                }

                if (pos in newPositions) {
                    wordMultiplier *= when (premium) {
                        PremiumSquare.DOUBLE_WORD -> 2
                        PremiumSquare.TRIPLE_WORD -> 3
                        else -> 1
                    }
                }
            }

            total += letterSum * wordMultiplier
        }

        if (placements.size == 7) total += 50  // bingo bonus

        return total
    }
}
