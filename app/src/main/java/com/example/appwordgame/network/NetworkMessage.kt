package com.example.appwordgame.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NetworkMessage(
    val type: MessageType,
    val fromPeer: String,
    val payload: String? = null,
)

@Serializable
enum class MessageType {
    PLAYER_JOIN,
    PLAYER_DISCONNECT,
    PLAYER_RECONNECT,
    PLAYER_LEAVE,
    PLAYER_INFO,
    START_GAME,
    GAME_MOVE,
    GAME_ACTION,
    GAME_STATE_UPDATE,
    ERROR,
}

data class PlayerInfo(
    val id: String,
    val nickname: String,
    val isConnected: Boolean = true,
)
