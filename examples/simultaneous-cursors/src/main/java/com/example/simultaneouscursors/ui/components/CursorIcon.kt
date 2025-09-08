package com.example.simultaneouscursors.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.simultaneouscursors.model.CursorShape

@Composable
fun CursorIcon(
    cursorShape: CursorShape,
    modifier: Modifier = Modifier,
    size: Int = 24
) {
    Canvas(
        modifier = modifier.size(size.dp)
    ) {
        when (cursorShape) {
            CursorShape.CURSOR -> drawCursor()
            CursorShape.HEART -> drawHeart()
            CursorShape.THUMBS -> drawThumbsUp()
            CursorShape.PEN -> drawPen()
        }
    }
}

private fun DrawScope.drawCursor() {
    val width = size.width
    val height = size.height

    // We rotate around the pen’s center
    rotate(degrees = 40f, pivot = Offset(width / 2, height / 2)) {
        val path = Path().apply {
            // Tail (left point)
            moveTo(width * 0.1f, height * 0.5f)

            // Top wing
            lineTo(width * 0.9f, height * 0.1f)

            // Mid inner fold
            lineTo(width * 0.65f, height * 0.5f)

            // Bottom wing
            lineTo(width * 0.9f, height * 0.9f)

            // Back to tail
            close()
        }

        drawPath(path = path, color = Color.Black)
    }
}

private fun DrawScope.drawHeart() {
    val path = Path().apply {
        val width = size.width
        val height = size.height

        // Left curve
        moveTo(width * 0.5f, height * 0.2f)

        // Left lobe: cubic curve to bottom tip
        cubicTo(
            width * 0.15f, height * 0.0f,    // control point 1 (left-top)
            width * 0.0f,  height * 0.35f,   // control point 2 (left-inner)
            width * 0.5f,  height * 0.8f     // end (bottom tip)
        )

        // Right lobe: cubic curve back to top center
        cubicTo(
            width * 1.0f,  height * 0.35f,   // control point 1 (right-inner)
            width * 0.85f, height * 0.0f,    // control point 2 (right-top)
            width * 0.5f,  height * 0.2f     // back to start
        )

        close()
    }

    drawPath(
        path = path,
        color = Color.Red
    )
}

private fun DrawScope.drawThumbsUp() {
    val width = size.width
    val height = size.height

    val path = Path().apply {
        // Wrist / base rectangle
        moveTo(width * 0.25f, height * 0.75f)
        lineTo(width * 0.25f, height * 0.45f)
        lineTo(width * 0.45f, height * 0.45f)
        lineTo(width * 0.45f, height * 0.75f)
        close()

        // Thumb
        moveTo(width * 0.45f, height * 0.45f)
        lineTo(width * 0.65f, height * 0.20f)
        lineTo(width * 0.75f, height * 0.25f)
        lineTo(width * 0.60f, height * 0.45f)
        close()

        // Fingers block
        moveTo(width * 0.45f, height * 0.45f)
        lineTo(width * 0.85f, height * 0.45f)
        lineTo(width * 0.85f, height * 0.75f)
        lineTo(width * 0.45f, height * 0.75f)
        close()
    }

    drawPath(path = path, color = Color.Yellow)
}

private fun DrawScope.drawPen() {
    val width = size.width
    val height = size.height

    // We rotate around the pen’s center
    rotate(degrees = 40f, pivot = Offset(width / 2, height / 2)) {
        // Pen body (rectangle)
        val body = Path().apply {
            moveTo(width * 0.35f, height * 0.1f)
            lineTo(width * 0.65f, height * 0.1f)
            lineTo(width * 0.65f, height * 0.55f)
            lineTo(width * 0.35f, height * 0.55f)
            close()
        }

        // Nib (triangle pointing down)
        val nib = Path().apply {
            moveTo(width * 0.35f, height * 0.55f)
            lineTo(width * 0.65f, height * 0.55f)
            lineTo(width * 0.50f, height * 0.90f) // tip
            close()
        }

        // Hole in nib (small circle)
        val holeCenter = Offset(width * 0.50f, height * 0.70f)
        val holeRadius = width * 0.05f

        drawPath(path = body, color = Color.Black)
        drawPath(path = nib, color = Color.Gray)
        drawCircle(color = Color.Black, radius = holeRadius, center = holeCenter)
    }
}
