package com.example.appwordgame.game

data class Tile(
    val letter: Char,
    val points: Int,
    val isBlank: Boolean = false
)
