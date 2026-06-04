package com.example.appwordgame.game

data class Position(val row: Int, val col: Int) {
    val isValid: Boolean get() = row in 0..14 && col in 0..14

    fun up() = Position(row - 1, col)
    fun down() = Position(row + 1, col)
    fun left() = Position(row, col - 1)
    fun right() = Position(row, col + 1)

    override fun toString() = "($row,$col)"
}
