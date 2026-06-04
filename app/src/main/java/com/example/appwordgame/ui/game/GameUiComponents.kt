package com.example.appwordgame.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appwordgame.game.GamePhase
import com.example.appwordgame.game.GameResult
import com.example.appwordgame.game.GameUiState
import com.example.appwordgame.game.Player
import com.example.appwordgame.game.Tile

private val TILE_BG       = Color(0xFFD4A017)
private val TILE_SELECTED = Color(0xFFFBBF24)
private val TILE_TEXT     = Color(0xFF1A0A00)

// ── Score bar ─────────────────────────────────────────────────────────────────

@Composable
fun ScoreBar(state: GameUiState, onResign: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1E2E))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            PlayerScore(
                label = "Gracz 1",
                score = state.scores[Player.ONE] ?: 0,
                active = state.currentPlayer == Player.ONE && state.phase == GamePhase.PLAYING,
                onResign = onResign
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${state.bagRemaining}",
                color = Color(0xFF9CB2CC),
                fontSize = 13.sp
            )
            if (state.lastMoveWords.isNotEmpty()) {
                Text(
                    text = state.lastMoveWords.joinToString(", ") + "  +${state.lastMoveScore}",
                    color = Color(0xFFFFD700),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            PlayerScore(
                label = "Gracz 2",
                score = state.scores[Player.TWO] ?: 0,
                active = state.currentPlayer == Player.TWO && state.phase == GamePhase.PLAYING,
                onResign = onResign,
                resignOnLeft = true
            )
        }
    }
}

@Composable
private fun PlayerScore(
    label: String,
    score: Int,
    active: Boolean,
    onResign: () -> Unit,
    resignOnLeft: Boolean = false
) {
    var showConfirm by remember { mutableStateOf(false) }

    val resignX = @Composable {
        if (active) {
            Text(
                text = "✕",
                color = Color(0xFFE53935),
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(
                        start = if (resignOnLeft) 0.dp else 6.dp,
                        end = if (resignOnLeft) 6.dp else 0.dp,
                        bottom = 8.dp
                    )
                    .clickable { showConfirm = true }
                    .padding(4.dp)
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (resignOnLeft) resignX()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = if (active) Color(0xFFFFD700) else Color(0xFF9CB2CC),
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = score.toString(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            if (active) {
                Text(text = "▶", color = Color(0xFFFFD700), fontSize = 10.sp)
            }
        }
        if (!resignOnLeft) resignX()
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = Color(0xFF0D1E2E),
            title = {
                Text("Rezygnacja", color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "Czy na pewno chcesz się poddać? Twój przeciwnik wygra partię.",
                    color = Color(0xFF9CB2CC),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showConfirm = false; onResign() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                ) { Text("Poddaję się") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Wróć", color = Color(0xFF9CB2CC))
                }
            }
        )
    }
}

// ── Rack ──────────────────────────────────────────────────────────────────────

@Composable
fun RackComposable(
    tiles: List<Tile>,
    selectedIndex: Int?,
    onTileTapped: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF091525))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tiles.forEachIndexed { index, tile ->
            RackTile(
                tile = tile,
                selected = index == selectedIndex,
                onTap = { onTileTapped(index) }
            )
            if (index < tiles.size - 1) Spacer(Modifier.width(4.dp))
        }
        // Empty slots
        repeat(7 - tiles.size) {
            EmptyRackSlot()
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun RackTile(tile: Tile, selected: Boolean, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(
                color = if (selected) TILE_SELECTED else TILE_BG,
                shape = RoundedCornerShape(4.dp)
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = Color.White,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (tile.isBlank && tile.letter == ' ') "★" else tile.letter.toString(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TILE_TEXT,
                lineHeight = 18.sp
            )
            if (tile.points > 0) {
                Text(
                    text = tile.points.toString(),
                    fontSize = 9.sp,
                    color = TILE_TEXT.copy(alpha = 0.7f),
                    lineHeight = 9.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyRackSlot() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color(0xFF1A2D40), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF1A3050), RoundedCornerShape(4.dp))
    )
}

// ── Action bar ────────────────────────────────────────────────────────────────

@Composable
fun ActionBar(
    state: GameUiState,
    onSubmit: () -> Unit,
    onPass: () -> Unit,
    onExchange: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPending = state.pendingPlacements.isNotEmpty()
    val canExchange = state.bagRemaining >= 7

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1828))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.moveError != null) {
            Text(
                text = state.moveError,
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onSubmit,
            enabled = hasPending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A6B3A))
        ) { Text("Zatwierdź", fontSize = 13.sp) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onShuffle,
                modifier = Modifier.weight(1f)
            ) { Text("Tasuj", fontSize = 13.sp) }

            OutlinedButton(
                onClick = onPass,
                enabled = !hasPending,
                modifier = Modifier.weight(1f)
            ) { Text("Pas", fontSize = 13.sp) }

            OutlinedButton(
                onClick = onExchange,
                enabled = !hasPending && canExchange,
                modifier = Modifier.weight(1f)
            ) { Text("Wymień", fontSize = 13.sp) }
        }
    }
}

// ── Exchange dialog ───────────────────────────────────────────────────────────

@Composable
fun ExchangeDialog(
    state: GameUiState,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(0xFF0D1E2E),
        title = {
            Text("Wymień litery", color = Color.White, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Wybierz litery do wymiany:",
                    color = Color(0xFF9CB2CC),
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.currentRack.forEachIndexed { index, tile ->
                        val selected = index in state.exchangeSelectedIndices
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if (selected) TILE_SELECTED else TILE_BG,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    if (selected) 2.dp else 0.dp,
                                    Color.White,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onToggle(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (tile.isBlank && tile.letter == ' ') "★" else tile.letter.toString(),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TILE_TEXT
                            )
                        }
                    }
                }
                Text(
                    text = "Zaznaczono: ${state.exchangeSelectedIndices.size}",
                    color = Color(0xFF9CB2CC),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = state.exchangeSelectedIndices.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A4480))
            ) { Text("Wymień") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Anuluj", color = Color(0xFF9CB2CC)) }
        }
    )
}

// ── Blank letter picker ───────────────────────────────────────────────────────

private val POLISH_LETTERS = "AĄBCĆDEĘFGHIJKLŁMNŃOÓPRSŚTUWYZŹŻ".toList()

@Composable
fun BlankLetterDialog(onLetterChosen: (Char) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1E2E),
        title = {
            Text("Wybierz literę dla blanka", color = Color.White, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                POLISH_LETTERS.chunked(8).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        row.forEach { letter ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(Color(0xFF1A3A5C), RoundedCornerShape(4.dp))
                                    .clickable { onLetterChosen(letter) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = letter.toString(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        repeat(8 - row.size) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj", color = Color(0xFF9CB2CC)) }
        }
    )
}

// ── End-game overlay ──────────────────────────────────────────────────────────

@Composable
fun EndGameOverlay(
    result: GameResult,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xCC000D1A), RoundedCornerShape(16.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val winnerText = when (result.winner) {
                Player.ONE -> "Gracz 1 wygrywa!"
                Player.TWO -> "Gracz 2 wygrywa!"
                null        -> "Remis!"
            }
            Text(
                text = winnerText,
                color = Color(0xFFFFD700),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Gracz 1: ${result.finalScores[Player.ONE] ?: 0}  |  Gracz 2: ${result.finalScores[Player.TWO] ?: 0}",
                color = Color.White,
                fontSize = 16.sp
            )
            val reasonText = when (result.reason.name) {
                "TILES_EXHAUSTED"           -> "Brak liter w worku"
                "CONSECUTIVE_SCORELESS_TURNS" -> "6 kolejnych pasów"
                "RESIGNATION"               -> "Rezygnacja"
                else -> ""
            }
            if (reasonText.isNotEmpty()) {
                Text(text = reasonText, color = Color(0xFF9CB2CC), fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onNewGame,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A4480))
            ) { Text("Nowa gra", fontSize = 15.sp) }
        }
    }
}
