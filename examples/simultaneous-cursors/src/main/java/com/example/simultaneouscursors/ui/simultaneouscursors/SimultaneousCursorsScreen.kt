package com.example.simultaneouscursors.ui.simultaneouscursors

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
fun SimultaneousCursorsScreen(
    modifier: Modifier = Modifier,
    viewModel: SimultaneousCursorsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()

    SimultaneousCursorsScreen(
        uiState = uiState,
        onSizeChanged = { size ->
            viewModel.updateScreenDimensions(
                size.width.toFloat(),
                size.height.toFloat(),
            )
        },
        onDragStart = viewModel::startDragging,
        onDragEnd = viewModel::endDragging,
        onDrag = viewModel::startDragging,
        onPresDown = viewModel::pressDown,
        onRelease = viewModel::release,
        onCursorShapeSelect = viewModel::updateCursorShape,
        modifier = modifier,
    )
}

@Composable
fun SimultaneousCursorsScreen(
    uiState: SimultaneousCursorsUiState,
    onSizeChanged: (IntSize) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onPresDown: (Offset) -> Unit,
    onRelease: () -> Unit,
    onCursorShapeSelect: (CursorShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        uiState.error != null -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Error: ${uiState.error}",
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .onSizeChanged { size ->
                        onSizeChanged(size)
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = onDragStart,
                            onDragEnd = onDragEnd,
                            onDrag = { change, _ ->
                                onDrag(change.position)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                onPresDown(offset)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    onRelease()
                                }
                            },
                        )
                    },
            ) {
                // Drawing canvas for pen lines (behind cursors)
                DrawingCanvas(
                    currentDrawingPreview = uiState.currentDrawingLinePreview,
                    activeDrawingLines = uiState.activeDrawingLines,
                )

                // Render all client cursors
                uiState.clients.forEach { clientPresence ->
                    if (clientPresence.presence.cursor.xPos > 0 ||
                        clientPresence.presence.cursor.yPos > 0
                    ) {
                        AnimatedCursor(
                            clientPresence = clientPresence,
                            modifier = Modifier,
                        )
                    }
                }

                // Cursor selection UI at the bottom
                CursorSelections(
                    selectedCursorShape = uiState.selectedCursorShape,
                    clientsCount = uiState.clients.size,
                    onCursorShapeSelect = onCursorShapeSelect,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}
