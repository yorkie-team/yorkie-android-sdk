package dev.yorkie.example.simultaneouscursors.ui.simultaneouscursors

import androidx.compose.foundation.Canvas
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

@Composable
fun CursorIcon(cursorShape: CursorShape, modifier: Modifier = Modifier) {
    when (cursorShape) {
        CursorShape.HEART -> {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Heart",
                modifier = modifier,
                tint = Color.Red,
            )
        }

        CursorShape.THUMBS -> {
            Icon(
                imageVector = Icons.Filled.ThumbUp,
                contentDescription = "Thumbs Up",
                modifier = modifier,
                tint = Color.Yellow,
            )
        }

        CursorShape.PEN -> {
            Canvas(modifier = modifier) {
                drawPen()
            }
        }

        CursorShape.CURSOR -> {
            Canvas(modifier = modifier) {
                drawCursor()
            }
        }
    }
}

private fun DrawScope.drawCursor() {
    val width = size.width
    val height = size.height

    // We rotate around the cursor’s center
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
            width * 0.15f,
            // control point 1 (left-top)
            height * 0.0f,
            width * 0.0f,
            // control point 2 (left-inner)
            height * 0.35f,
            width * 0.5f,
            // end (bottom tip)
            height * 0.8f,
        )

        // Right lobe: cubic curve back to top center
        cubicTo(
            width * 1.0f,
            // control point 1 (right-inner)
            height * 0.35f,
            width * 0.85f,
            // control point 2 (right-top)
            height * 0.0f,
            width * 0.5f,
            // back to start
            height * 0.2f,
        )

        close()
    }

    drawPath(
        path = path,
        color = Color.Red,
    )
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
