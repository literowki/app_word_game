package com.example.appwordgame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.appwordgame.ui.game.GameScreen
import com.example.appwordgame.ui.theme.AppWordGameTheme
import com.example.appwordgame.webrtc.ChatMessage
import com.example.appwordgame.webrtc.WebRtcChatEngine
import com.example.appwordgame.webrtc.WebRtcChatUiState

private enum class Screen { GAME, CHAT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppWordGameTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF08111E)) {
                    var screen by rememberSaveable { mutableStateOf(Screen.GAME) }
                    Column(modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        // Tab bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF060E1A))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TabButton(
                                label = "Gra",
                                selected = screen == Screen.GAME,
                                onClick = { screen = Screen.GAME },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                label = "WebRTC",
                                selected = screen == Screen.CHAT,
                                onClick = { screen = Screen.CHAT },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        when (screen) {
                            Screen.GAME -> GameScreen(modifier = Modifier.weight(1f))
                            Screen.CHAT -> PeerChatApp(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun PeerChatApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember(context) { WebRtcChatEngine(context) }
    val state by engine.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    DisposableEffect(engine) {
        onDispose { engine.close() }
    }

    PeerChatScreen(
        state = state,
        clipboardManager = clipboardManager,
        onCreateOffer = engine::createOffer,
        onCreateAnswer = engine::createAnswer,
        onApplyRemoteDescription = engine::setRemoteDescription,
        onApplyRemoteCandidates = engine::addRemoteCandidates,
        onReset = engine::reset,
        onSendMessage = engine::sendMessage,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerChatScreen(
    state: WebRtcChatUiState,
    clipboardManager: ClipboardManager,
    onCreateOffer: () -> Unit,
    onCreateAnswer: () -> Unit,
    onApplyRemoteDescription: (String) -> Unit,
    onApplyRemoteCandidates: (String) -> Unit,
    onReset: () -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var remoteDescriptionText by rememberSaveable { mutableStateOf("") }
    var remoteCandidatesText by rememberSaveable { mutableStateOf("") }
    var messageDraft by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()

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
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 24.dp)
                .width(220.dp)
                .height(220.dp)
                .background(Color(0x22FFB703), RoundedCornerShape(120.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 120.dp)
                .width(180.dp)
                .height(180.dp)
                .background(Color(0x223A86FF), RoundedCornerShape(100.dp))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xE6121B2B)),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Peer Chat",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Manual WebRTC data-channel chat for Android. Copy the SDP and ICE data between this app and the browser instance.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB8C6D9)
                        )
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusChip(label = "Connection", value = state.connectionState)
                        StatusChip(label = "Channel", value = state.dataChannelState)
                        StatusChip(label = "Role", value = state.role)
                        StatusChip(label = "ICE", value = state.iceGatheringState)
                        StatusChip(label = "Local id", value = state.localPeerId.ifBlank { "-" })
                        StatusChip(label = "Relay", value = state.relayPeerId ?: "-")
                        StatusChip(
                            label = "Peers",
                            value = if (state.connectedPeers.isEmpty()) "None" else state.connectedPeers.joinToString(", ")
                        )
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onCreateOffer) { Text("Create offer") }
                        Button(onClick = onCreateAnswer) { Text("Create answer") }
                        OutlinedButton(onClick = onReset) { Text("Reset") }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xE6111723)),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SectionTitle(title = "Signaling")

                    SignalSection(
                        title = "Local description",
                        value = state.localDescription,
                        placeholder = "Create an offer or answer to generate local SDP.",
                        readOnly = true,
                        onCopy = {
                            if (state.localDescription.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(state.localDescription))
                            }
                        }
                    )

                    SignalSection(
                        title = "Remote description",
                        value = remoteDescriptionText,
                        placeholder = "Paste the remote offer or answer JSON here.",
                        readOnly = false,
                        onValueChange = { remoteDescriptionText = it },
                        onAction = { onApplyRemoteDescription(remoteDescriptionText) },
                        actionLabel = "Apply remote"
                    )

                    SignalSection(
                        title = "Local ICE candidates",
                        value = state.localCandidates,
                        placeholder = "Generated candidates appear here.",
                        readOnly = true,
                        onCopy = {
                            if (state.localCandidates.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(state.localCandidates))
                            }
                        }
                    )

                    SignalSection(
                        title = "Remote ICE candidates",
                        value = remoteCandidatesText,
                        placeholder = "Paste one JSON candidate per line.",
                        readOnly = false,
                        minLines = 4,
                        onValueChange = { remoteCandidatesText = it },
                        onAction = { onApplyRemoteCandidates(remoteCandidatesText) },
                        actionLabel = "Add candidates"
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xE6111723)),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionTitle(title = "Chat")

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1320)),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (state.messages.isEmpty()) {
                                Text(
                                    text = "No messages yet. Open the connection first, then send text over the data channel.",
                                    color = Color(0xFF8EA0B8)
                                )
                            } else {
                                state.messages.forEach { message ->
                                    MessageBubble(message = message)
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = messageDraft,
                            onValueChange = { messageDraft = it },
                            placeholder = { Text("Type a message") },
                            minLines = 1,
                            maxLines = 4,
                            shape = RoundedCornerShape(18.dp),
                            enabled = state.isDataChannelOpen
                        )
                        Button(
                            onClick = {
                                val trimmed = messageDraft.trim()
                                if (trimmed.isNotEmpty()) {
                                    onSendMessage(trimmed)
                                    messageDraft = ""
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                            enabled = state.isDataChannelOpen,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Text("Send")
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xCC0B1320)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Event log",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(onClick = { clipboardManager.setText(AnnotatedString(state.logLines.joinToString("\n"))) }) {
                            Text("Copy log")
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.logLines.takeLast(8).forEach { line ->
                            Text(
                                text = line,
                                color = Color(0xFFBAC8DA),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun StatusChip(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF152538)),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = Color(0xFF9CB2CC), style = MaterialTheme.typography.labelMedium)
            Text(text = value, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SignalSection(
    title: String,
    value: String,
    placeholder: String,
    readOnly: Boolean,
    minLines: Int = 5,
    onCopy: (() -> Unit)? = null,
    onValueChange: ((String) -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String = "Apply"
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            when {
                onCopy != null -> {
                    OutlinedButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("Copy")
                    }
                }
                onAction != null -> {
                    OutlinedButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(actionLabel)
                    }
                }
            }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = { onValueChange?.invoke(it) },
            placeholder = { Text(placeholder) },
            readOnly = readOnly,
            minLines = minLines,
            maxLines = 12,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.mine) Color(0xFF163B2B) else Color(0xFF14273D)
    val alignment = if (message.mine) Arrangement.End else Arrangement.Start
    val sender = if (message.mine) "You" else message.fromPeerId ?: "Peer"

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = alignment) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = sender, color = Color(0xFF9CB2CC), style = MaterialTheme.typography.labelSmall)
                Text(text = message.text, color = Color.White)
                Text(text = message.timestamp, color = Color(0xFF9CB2CC), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
