package com.example.appwordgame.ui.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appwordgame.network.PlayerInfo

@Composable
fun CreateGameScreen(
    viewModel: LobbyViewModel,
    onAddPlayer: () -> Unit,
    onStartGame: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1A3A5A), Color(0xFF08111E), Color(0xFF04070D)),
                    radius = 1600f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Game Lobby",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Host: ${state.localNickname}",
                color = Color(0xFFFFD700),
                fontSize = 16.sp,
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = Color(0xFFE53935),
                    fontSize = 14.sp,
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xE6121B2B)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Players", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.players.size} connected",
                            color = Color(0xFF9CB2CC),
                            fontSize = 14.sp,
                        )
                    }
                    if (state.players.isEmpty()) {
                        Text(
                            "No players yet. Tap \"Add player\" to invite.",
                            color = Color(0xFF8EA0B8),
                            fontSize = 14.sp,
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.players) { player ->
                                PlayerRow(player)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onAddPlayer,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Add player", fontSize = 16.sp)
            }

            Button(
                onClick = onStartGame,
                enabled = state.canStartGame,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A6B3A),
                    disabledContainerColor = Color(0xFF2A3A2A),
                ),
            ) {
                Text("Start game", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Leave lobby", color = Color(0xFF9CB2CC))
            }
        }
    }
}

@Composable
private fun PlayerRow(player: PlayerInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF152538)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(player.nickname, color = Color.White, fontSize = 15.sp)
            Text(
                if (player.isConnected) "Online" else "Offline",
                color = if (player.isConnected) Color(0xFF4CAF50) else Color(0xFFE53935),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
