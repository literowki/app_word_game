package com.example.appwordgame.game

enum class Player {
    ONE, TWO, THREE, FOUR;

    companion object {
        fun activePlayers(count: Int): List<Player> = entries.take(count.coerceIn(2, 4))

        fun cycle(players: List<Player>, current: Player): Player {
            val idx = players.indexOf(current)
            return players[(idx + 1) % players.size]
        }

        fun others(players: List<Player>, current: Player): List<Player> =
            players.filter { it != current }
    }
}
