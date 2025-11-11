package com.example.simultaneouscursors.ui.simultaneouscursors

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun AnimatedCursor(clientPresence: ClientPresence, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val cursorSize = 24.dp

    // Animate cursor position
    val animatedX by animateFloatAsState(
        targetValue = clientPresence.presence.cursor.xPos.toFloat(),
        animationSpec = tween(durationMillis = 10),
        label = "cursor_x",
    )

    val animatedY by animateFloatAsState(
        targetValue = clientPresence.presence.cursor.yPos.toFloat(),
        animationSpec = tween(durationMillis = 10),
        label = "cursor_y",
    )

    // Animate scale for pointer down effect
    val scale by animateFloatAsState(
        targetValue = if (clientPresence.presence.pointerDown) 1.2f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "cursor_scale",
    )

    val halfCursorPx = with(density) { cursorSize.toPx() / 2 }

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = animatedX - halfCursorPx
                translationY = animatedY - halfCursorPx
            },
    ) {
        // Main cursor
        CursorIcon(
            cursorShape = clientPresence.presence.cursorShape,
            modifier = Modifier.size((cursorSize.value * scale).dp),
        )

        // User name label positioned above the cursor
        val name = clientPresence.presence.name ?: "Anonymous"
        val color = clientPresence.presence.color?.toColor() ?: Color.Black
        Text(
            text = if (clientPresence.presence.isMyself) {
                "$name (Me)"
            } else {
                name
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .offset(x = 8.dp, y = (-16).dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.9f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // Special effects for certain cursor shapes
        when (clientPresence.presence.cursorShape) {
            CursorShape.HEART, CursorShape.THUMBS -> {
                if (!clientPresence.presence.pointerDown) {
                    AnimationEffect(
                        cursorShape = clientPresence.presence.cursorShape,
                    )
                }
            }

            CursorShape.PEN -> {
                if (clientPresence.presence.pointerDown) {
                    PenTrail(
                        color = Color.Black,
                    )
                }
            }

            else -> {
                Unit
            }
        }
    }
}

@Composable
private fun AnimationEffect(cursorShape: CursorShape, modifier: Modifier = Modifier) {
    var particles by remember { mutableStateOf(emptyList<FloatingParticle>()) }

    // Generate new particles when effect starts
    LaunchedEffect(Unit) {
        val newParticles = (1..5).map { index ->
            FloatingParticle(
                id = index,
                startX = Random.nextFloat() * 60f - 30f,
                startY = -30f,
                targetY = -200f - Random.nextFloat() * 60f,
                horizontalDrift = Random.nextFloat() * 20f - 10f,
                delay = index * 100L,
            )
        }
        particles = newParticles
    }

    // Render each particle
    particles.forEach { particle ->
        FloatingParticleView(
            particle = particle,
            cursorShape = cursorShape,
            modifier = modifier,
        )
    }
}

data class FloatingParticle(
    val id: Int,
    val startX: Float,
    val startY: Float,
    val targetY: Float,
    val horizontalDrift: Float,
    val delay: Long,
)

@Composable
private fun FloatingParticleView(
    particle: FloatingParticle,
    cursorShape: CursorShape,
    modifier: Modifier = Modifier,
) {
    var animationStarted by remember { mutableStateOf(false) }

    // Start animation after delay
    LaunchedEffect(particle.id) {
        delay(particle.delay)
        animationStarted = true
    }

    // Animate vertical position
    val animatedY by animateFloatAsState(
        targetValue = if (animationStarted) {
            particle.targetY
        } else {
            particle.startY
        },
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "particle_y_${particle.id}",
    )

    // Animate horizontal drift
    val animatedX by animateFloatAsState(
        targetValue = if (animationStarted) {
            particle.startX + particle.horizontalDrift
        } else {
            particle.startX
        },
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "particle_x_${particle.id}",
    )

    // Animate opacity (fade out as it goes up)
    val alpha by animateFloatAsState(
        targetValue = if (animationStarted) 0f else 1f,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "particle_alpha_${particle.id}",
    )

    // Animate scale (slight grow and shrink)
    val scale by animateFloatAsState(
        targetValue = if (animationStarted) 0.8f else 1.2f,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "particle_scale_${particle.id}",
    )

    Box(
        modifier = modifier
            .offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scale
                this.scaleY = scale
            },
    ) {
        CursorIcon(
            cursorShape = cursorShape,
            modifier = Modifier
                .size(20.dp),
        )
    }
}

@Composable
private fun PenTrail(color: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.size(8.dp),
    ) {
        drawCircle(
            color = color,
            radius = size.width / 4,
            center = Offset(size.width / 2, size.height / 2),
        )
    }
}

private fun String.toColor(): Color {
    return Color(this.toColorInt())
}
