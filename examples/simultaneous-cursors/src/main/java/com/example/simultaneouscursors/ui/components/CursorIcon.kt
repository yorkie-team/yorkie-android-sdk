package com.example.simultaneouscursors.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.simultaneouscursors.model.CursorShape

@Composable
fun CursorIcon(
    cursorShape: CursorShape,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colors.primary,
    size: Int = 24
) {
    Canvas(
        modifier = modifier.size(size.dp)
    ) {
        when (cursorShape) {
            CursorShape.CURSOR -> drawCursor(color)
            CursorShape.HEART -> drawHeart(color)
            CursorShape.THUMBS -> drawThumbsUp(color)
            CursorShape.PEN -> drawPen(color)
        }
    }
}

private fun DrawScope.drawCursor(color: Color) {
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, size.height * 0.8f)
        lineTo(size.width * 0.3f, size.height * 0.6f)
        lineTo(size.width * 0.5f, size.height)
        lineTo(size.width * 0.7f, size.height * 0.7f)
        lineTo(size.width * 0.6f, size.height * 0.4f)
        close()
    }
    
    drawPath(
        path = path,
        color = color
    )
    
    drawPath(
        path = path,
        color = Color.White,
        style = Stroke(width = 2.dp.toPx())
    )
}

private fun DrawScope.drawHeart(color: Color) {
    val path = Path().apply {
        val width = size.width
        val height = size.height
        
        // Left curve
        moveTo(width * 0.5f, height * 0.3f)
        cubicTo(
            width * 0.2f, height * 0.1f,
            -width * 0.25f, height * 0.6f,
            width * 0.5f, height
        )
        
        // Right curve
        cubicTo(
            width * 1.25f, height * 0.6f,
            width * 0.8f, height * 0.1f,
            width * 0.5f, height * 0.3f
        )
        close()
    }
    
    drawPath(
        path = path,
        color = color
    )
}

private fun DrawScope.drawThumbsUp(color: Color) {
    // Thumb
    drawCircle(
        color = color,
        radius = size.width * 0.15f,
        center = Offset(size.width * 0.3f, size.height * 0.25f)
    )
    
    // Hand
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.2f, size.height * 0.4f),
        size = androidx.compose.ui.geometry.Size(
            size.width * 0.6f,
            size.height * 0.4f
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
    )
}

private fun DrawScope.drawPen(color: Color) {
    // Pen body
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.3f, size.height * 0.1f),
        size = androidx.compose.ui.geometry.Size(
            size.width * 0.4f,
            size.height * 0.7f
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
    )
    
    // Pen tip
    val tipPath = Path().apply {
        moveTo(size.width * 0.5f, size.height * 0.8f)
        lineTo(size.width * 0.4f, size.height * 0.9f)
        lineTo(size.width * 0.6f, size.height * 0.9f)
        close()
    }
    
    drawPath(
        path = tipPath,
        color = color
    )
} 