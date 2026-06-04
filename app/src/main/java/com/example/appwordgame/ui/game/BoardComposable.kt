package com.example.appwordgame.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appwordgame.game.Board
import com.example.appwordgame.game.GameUiState
import com.example.appwordgame.game.Position
import com.example.appwordgame.game.PremiumSquare
import com.example.appwordgame.game.Tile

private val TW_COLOR    = Color(0xFF9B1C1C)
private val DW_COLOR    = Color(0xFF92400E)
private val TL_COLOR    = Color(0xFF1E3A8A)
private val DL_COLOR    = Color(0xFF1E4D6B)
private val EMPTY_COLOR = Color(0xFF0D1E2E)
private val TILE_COLOR  = Color(0xFFD4A017)
private val PENDING_COLOR = Color(0xFFFBBF24)
private val GRID_LINE   = Color(0xFF1A3050)

@Composable
fun BoardComposable(
    state: GameUiState,
    onCellTapped: (Position) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val cellSize: Dp = maxWidth / 15
        Column {
            for (row in 0..14) {
                Row {
                    for (col in 0..14) {
                        val pos = Position(row, col)
                        BoardCell(
                            pos = pos,
                            committedTile = state.boardTiles[pos],
                            pendingTile = state.pendingPlacements[pos],
                            premium = Board.PREMIUM_MAP[pos] ?: PremiumSquare.NONE,
                            isCenter = pos == Board.CENTER,
                            cellSize = cellSize,
                            onTap = { onCellTapped(pos) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCell(
    pos: Position,
    committedTile: Tile?,
    pendingTile: Tile?,
    premium: PremiumSquare,
    isCenter: Boolean,
    cellSize: Dp,
    onTap: () -> Unit
) {
    val bgColor = when {
        committedTile != null -> TILE_COLOR
        pendingTile != null   -> PENDING_COLOR
        isCenter              -> DW_COLOR
        else -> when (premium) {
            PremiumSquare.TRIPLE_WORD   -> TW_COLOR
            PremiumSquare.DOUBLE_WORD   -> DW_COLOR
            PremiumSquare.TRIPLE_LETTER -> TL_COLOR
            PremiumSquare.DOUBLE_LETTER -> DL_COLOR
            PremiumSquare.NONE          -> EMPTY_COLOR
        }
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            .background(bgColor)
            .border(0.5.dp, GRID_LINE)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        val tile = committedTile ?: pendingTile
        if (tile != null) {
            TileContent(tile = tile, cellSize = cellSize, dimmed = false)
        } else {
            PremiumLabel(premium = premium, isCenter = isCenter, cellSize = cellSize)
        }
    }
}

@Composable
private fun TileContent(tile: Tile, cellSize: Dp, dimmed: Boolean) {
    val letterSize = (cellSize.value * 0.52f).coerceAtLeast(8f).sp
    val pointSize  = (cellSize.value * 0.28f).coerceAtLeast(5f).sp
    val textColor  = if (dimmed) Color(0xFF5D4000) else Color(0xFF1A0A00)

    Column(
        modifier = Modifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (tile.isBlank) tile.letter.takeIf { it != ' ' }?.toString() ?: "★" else tile.letter.toString(),
            fontSize = letterSize,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center,
            lineHeight = letterSize
        )
        if (tile.points > 0) {
            Text(
                text = tile.points.toString(),
                fontSize = pointSize,
                color = textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = pointSize
            )
        }
    }
}

@Composable
private fun PremiumLabel(premium: PremiumSquare, isCenter: Boolean, cellSize: Dp) {
    val labelSize = (cellSize.value * 0.25f).coerceAtLeast(5f).sp
    val (text, color) = when {
        isCenter                          -> "★" to Color(0xFFFFD700)
        premium == PremiumSquare.TRIPLE_WORD   -> "TW" to Color(0xFFFFAAAA)
        premium == PremiumSquare.DOUBLE_WORD   -> "DW" to Color(0xFFFFCC88)
        premium == PremiumSquare.TRIPLE_LETTER -> "TL" to Color(0xFF88BBFF)
        premium == PremiumSquare.DOUBLE_LETTER -> "DL" to Color(0xFF77AADD)
        else -> return
    }
    Text(
        text = text,
        fontSize = labelSize,
        fontWeight = FontWeight.Bold,
        color = color,
        textAlign = TextAlign.Center
    )
}
