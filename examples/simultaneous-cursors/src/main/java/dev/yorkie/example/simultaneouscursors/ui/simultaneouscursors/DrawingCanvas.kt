package dev.yorkie.example.simultaneouscursors.ui.simultaneouscursors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun DrawingCanvas(
    modifier: Modifier = Modifier,
    currentDrawingPreview: List<Offset> = emptyList(),
    activeDrawingLines: Map<String, List<Offset>> = emptyMap(),
) {
    Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        // No persistent drawing lines anymore - they disappear when drawing stops

        // Draw active drawing lines from other clients (being drawn in real-time)
        activeDrawingLines.forEach { (clientId, points) ->
            if (points.size >= 2) {
                val clientColor = Color.Black
                val path = Path()

                // Start the path at the first point
                val firstPoint = points.first()
                path.moveTo(firstPoint.x, firstPoint.y)

                // Add lines to subsequent points
                points.drop(1).forEach { point ->
                    path.lineTo(point.x, point.y)
                }

                // Draw the active path with a slightly more transparent style
                drawPath(
                    path = path,
                    color = clientColor.copy(alpha = 0.7f),
                    style = Stroke(
                        width = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        // Draw current drawing preview (while user is actively drawing)
        if (currentDrawingPreview.size >= 2) {
            val path = Path()

            // Start the path at the first point
            val firstPoint = currentDrawingPreview.first()
            path.moveTo(firstPoint.x, firstPoint.y)

            // Add lines to subsequent points
            currentDrawingPreview.drop(1).forEach { point ->
                path.lineTo(point.x, point.y)
            }

            // Draw the preview path with a slightly different style
            drawPath(
                path = path,
                color = Color.Gray,
                style = Stroke(
                    width = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}
