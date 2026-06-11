package com.example.appwordgame.ui.lobby

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AddPlayerScreen(
    viewModel: AddPlayerViewModel,
    onGenerateInvitation: suspend () -> String,
    onConnect: suspend (String) -> Boolean,
    onBack: () -> Unit,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val answerText = remember { mutableStateOf("") }
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
                text = "Add Player",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            when (state.step) {
                AddPlayerStep.GENERATE_INVITATION -> {
                    Text(
                        "Generate an invitation string to send to the other player.",
                        color = Color(0xFF9CB2CC),
                        fontSize = 14.sp,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    Log.d("AddPlayerScreen", "Generating invitation")
                                    val invitation = onGenerateInvitation()
                                    Log.d("AddPlayerScreen", "Generated invitation: $invitation")
                                    viewModel.onInvitationGenerated(invitation)
                                } catch (e: Exception) {
                                    viewModel.onError(e.message ?: "Failed to generate invitation")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Generate invitation")
                    }
                }

                AddPlayerStep.WAITING_FOR_ANSWER -> {
                    Text(
                        "Share this invitation string with the other player:",
                        color = Color(0xFF9CB2CC),
                        fontSize = 14.sp,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF152538)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            state.invitationString,
                            color = Color(0xFFB8C6D9),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(state.invitationString))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy invitation")
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Paste the answer from the other player below:",
                        color = Color(0xFF9CB2CC),
                        fontSize = 14.sp,
                    )
                    OutlinedTextField(
                        value = answerText.value,
                        onValueChange = { answerText.value = it },
                        label = { Text("Answer string") },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = {
                            if (answerText.value.isNotBlank()) {
                                viewModel.onAnswerSubmitted()
                                scope.launch {
                                    try {
                                        val success = onConnect(answerText.value.trim())
                                        if (success) {
                                            viewModel.onConnected()
                                        } else {
                                            viewModel.onError("Connection failed")
                                        }
                                    } catch (e: Exception) {
                                        viewModel.onError(e.message ?: "Connection failed")
                                    }
                                }
                            }
                        },
                        enabled = answerText.value.isNotBlank() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Connect")
                    }
                }

                AddPlayerStep.CONNECTING -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFFD700))
                    }
                    Text(
                        "Connecting...",
                        color = Color(0xFF9CB2CC),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                AddPlayerStep.CONNECTED -> {
                    Text(
                        "Player connected successfully!",
                        color = Color(0xFF4CAF50),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Button(
                        onClick = onConnected,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Back to lobby")
                    }
                }

                AddPlayerStep.ERROR -> {
                    Text(
                        "Error: ${state.error}",
                        color = Color(0xFFE53935),
                        fontSize = 14.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.onReset()
                            answerText.value = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try again", color = Color(0xFF9CB2CC))
                    }
                }
            }

            if (state.step != AddPlayerStep.CONNECTED) {
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
