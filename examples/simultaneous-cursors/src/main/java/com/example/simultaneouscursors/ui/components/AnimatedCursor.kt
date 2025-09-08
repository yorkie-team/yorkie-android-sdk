package com.example.simultaneouscursors.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.simultaneouscursors.model.ClientPresence
import com.example.simultaneouscursors.model.CursorShape
import kotlin.math.roundToInt

@Composable
fun AnimatedCursor(
    clientPresence: ClientPresence,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cursorSize = 24.dp

    // Animate cursor position
    val animatedX by animateFloatAsState(
        targetValue = clientPresence.presence.cursor.xPos.toFloat(),
        animationSpec = tween(durationMillis = 100),
        label = "cursor_x"
    )

    val animatedY by animateFloatAsState(
        targetValue = clientPresence.presence.cursor.yPos.toFloat(),
        animationSpec = tween(durationMillis = 100),
        label = "cursor_y"
    )

    // Animate scale for pointer down effect
    val scale by animateFloatAsState(
        targetValue = if (clientPresence.presence.pointerDown) 1.2f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "cursor_scale"
    )

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (animatedX - with(density) { cursorSize.toPx() / 2 }).roundToInt(),
                    y = (animatedY - with(density) { cursorSize.toPx() / 2 }).roundToInt()
                )
            }
    ) {
        // Main cursor
        CursorIcon(
            cursorShape = clientPresence.presence.cursorShape,
            modifier = Modifier.size((cursorSize.value * scale).dp),
            size = (24 * scale).roundToInt()
        )

        // Special effects for certain cursor shapes
        when (clientPresence.presence.cursorShape) {
            CursorShape.HEART, CursorShape.THUMBS -> {
                if (clientPresence.presence.pointerDown) {
                    AnimationEffect(
                        cursorShape = clientPresence.presence.cursorShape,
                    )
                }
            }
            CursorShape.PEN -> {
                if (clientPresence.presence.pointerDown) {
                    PenTrail(
                        color = generateColorForClient(clientPresence.clientID)
                    )
                }
            }
            else -> { /* No special effect */ }
        }
    }
}

@Composable
private fun AnimationEffect(
    cursorShape: CursorShape,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = 1.5f,
        animationSpec = tween(durationMillis = 300),
        label = "effect_scale"
    )

    CursorIcon(
        cursorShape = cursorShape,
        modifier = modifier.size((36 * scale).dp),
        size = (36 * scale).roundToInt()
    )
}

@Composable
private fun PenTrail(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.size(8.dp)
    ) {
        drawCircle(
            color = color,
            radius = size.width / 4,
            center = Offset(size.width / 2, size.height / 2)
        )
    }
}

private fun generateColorForClient(clientId: String): Color {
    // Generate a consistent color for each client based on their ID
    val hash = clientId.hashCode()
    val colors = listOf(
        Color(0xFF2196F3), // Blue
        Color(0xFF4CAF50), // Green
        Color(0xFFFF9800), // Orange
        Color(0xFF9C27B0), // Purple
        Color(0xFFF44336), // Red
        Color(0xFF00BCD4), // Cyan
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF795548), // Brown
    )
    return colors[Math.abs(hash) % colors.size]
}
