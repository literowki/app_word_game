package com.example.appwordgame.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WelcomeScreen(
    onCreateGame: (String) -> Unit,
    onJoinGame: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var nickname by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier
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
                .fillMaxWidth(0.5f)
                .height(220.dp)
                .background(Color(0x22FFB703), RoundedCornerShape(120.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 120.dp)
                .fillMaxWidth(0.4f)
                .height(180.dp)
                .background(Color(0x223A86FF), RoundedCornerShape(100.dp))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Literówki",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD700),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Gra słowna dla przyjaciół",
                fontSize = 16.sp,
                color = Color(0xFF9CB2CC),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))

            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A4480)),
            ) {
                Text("Create game", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onJoinGame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A6B3A)),
            ) {
                Text("Join game", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF0D1E2E),
            title = {
                Text("Create game", color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nickname.isNotBlank()) {
                            showCreateDialog = false
                            onCreateGame(nickname.trim())
                        }
                    },
                    enabled = nickname.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = Color(0xFF9CB2CC))
                }
            }
        )
    }
}
