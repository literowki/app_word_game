package com.example.appwordgame.ui.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appwordgame.network.PlayerInfo
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import kotlinx.coroutines.launch

@Composable
fun JoinGameScreen(
    viewModel: JoinGameViewModel,
    onGenerateAnswer: suspend (String, String) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Join Game",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            when (state.status) {
                JoinStatus.INPUT -> {
                    OutlinedTextField(
                        value = state.nickname,
                        onValueChange = { viewModel.onNicknameChanged(it) },
                        label = { Text("Your nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = state.invitationString,
                        onValueChange = { viewModel.onInvitationChanged(it) },
                        label = { Text("Paste invitation from host") },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        shape = RoundedCornerShape(12.dp),
                    )

                    Button(
                        onClick = {
                            if (state.nickname.isNotBlank()) {
                                viewModel.onGenerating()

                                Nearby.getConnectionsClient(context).startDiscovery(
                                    "com.example.appwordgame",
                                    object : EndpointDiscoveryCallback() {
                                        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                                            // host found, connect automatically
                                            Nearby.getConnectionsClient(context).requestConnection(
                                                state.nickname.trim(),
                                                endpointId,
                                                object: ConnectionLifecycleCallback() {
                                                    override fun onConnectionInitiated(hostId: String, cInfo: ConnectionInfo) {
                                                        // accept BT connection
                                                        Nearby.getConnectionsClient(context).acceptConnection(hostId, object: PayloadCallback() {
                                                            override fun onPayloadReceived(id: String, payload: Payload) {
                                                                val receivedInvitation = String(payload.asBytes()!!)

                                                                viewModel.onInvitationChanged(receivedInvitation)

                                                                scope.launch {
                                                                    try {
                                                                        val answer = onGenerateAnswer(
                                                                            state.nickname.trim(),
                                                                            receivedInvitation.trim()
                                                                        )
                                                                        viewModel.onAnswerGenerated(answer)

                                                                        // answer
                                                                        val responsePayload = Payload.fromBytes(answer.toByteArray())
                                                                        Nearby.getConnectionsClient(context).sendPayload(hostId, responsePayload)

                                                                        // off BT -> WebRTC goes further
                                                                        Nearby.getConnectionsClient(context).stopDiscovery()

                                                                    } catch (e: Exception) {
                                                                        viewModel.onError(e.message ?: "Failed")
                                                                    }
                                                                }
                                                            }
                                                            override fun onPayloadTransferUpdate(p0: String, p1: PayloadTransferUpdate) {}
                                                        })
                                                    }

                                                    override fun onConnectionResult(
                                                        p0: String,
                                                        p1: ConnectionResolution
                                                    ) {}

                                                    override fun onDisconnected(p0: String) {}
                                                }
                                            )
                                        }

                                        override fun onEndpointLost(endpointId: String) {}
                                    },
                                    DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
                                )
                            }
                        },
                        /*
                        scope.launch {
                                    try {
                                        val answer = onGenerateAnswer(
                                            state.nickname.trim(),
                                            state.invitationString.trim()
                                        )
                                        viewModel.onAnswerGenerated(answer)
                                    } catch (e: Exception) {
                                        viewModel.onError(e.message ?: "Failed")
                                    }
                                }
                         */
                        enabled = state.nickname.isNotBlank() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("search & join game")
                    }
                }

                JoinStatus.GENERATING_ANSWER -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFFD700))
                    }
                }

                JoinStatus.CONNECTING -> {
                    Text(
                        "Send this answer string to the host:",
                        color = Color(0xFF9CB2CC),
                        fontSize = 14.sp,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF152538)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            state.answerString,
                            color = Color(0xFFB8C6D9),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(state.answerString))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy answer")
                    }
                    Text(
                        "Waiting for host to complete connection...",
                        color = Color(0xFF9CB2CC),
                        fontSize = 14.sp,
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFFD700))
                    }
                }

                JoinStatus.IN_LOBBY -> {
                    Text(
                        "Connected!",
                        color = Color(0xFF4CAF50),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Host: ${state.hostNickname}",
                        color = Color(0xFFFFD700),
                        fontSize = 16.sp,
                    )
                    Text(
                        "Status: Waiting for host to start the game...",
                        color = Color(0xFF9CB2CC),
                        fontSize = 14.sp,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xE6121B2B)),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Players", color = Color.White, fontWeight = FontWeight.SemiBold)
                            state.players.forEach { player ->
                                PlayerJoinRow(player)
                            }
                        }
                    }
                }

                JoinStatus.ERROR -> {
                    Text(
                        "Error: ${state.error}",
                        color = Color(0xFFE53935),
                        fontSize = 14.sp,
                    )
                }
            }

            if (state.status != JoinStatus.IN_LOBBY) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Back", color = Color(0xFF9CB2CC))
                }
            }
        }
    }
}

@Composable
private fun PlayerJoinRow(player: PlayerInfo) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(player.nickname, color = Color.White, fontSize = 15.sp)
        Text(
            if (player.isConnected) "Online" else "Offline",
            color = if (player.isConnected) Color(0xFF4CAF50) else Color(0xFFE53935),
            fontSize = 13.sp,
        )
    }
}
