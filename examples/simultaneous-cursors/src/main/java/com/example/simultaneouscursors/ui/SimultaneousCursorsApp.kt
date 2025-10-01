package com.example.simultaneouscursors.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.simultaneouscursors.model.CursorShape
import com.example.simultaneouscursors.ui.components.AnimatedCursor
import com.example.simultaneouscursors.ui.components.CursorSelections
import com.example.simultaneouscursors.ui.components.DrawingCanvas
import com.example.simultaneouscursors.viewmodel.SimultaneousCursorsUiState
import com.example.simultaneouscursors.viewmodel.SimultaneousCursorsViewModel

@Composable
fun SimultaneousCursorsApp(
    modifier: Modifier = Modifier,
    viewModel: SimultaneousCursorsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()

    SimultaneousCursorsContent(
        uiState = uiState,
        onSizeChanged = { size ->
            viewModel.updateScreenDimensions(
                size.width.toFloat(),
                size.height.toFloat(),
            )
        },
        onDragStart = { offset ->
            viewModel.updatePointerDown(true)
            viewModel.updateCursorPosition(offset.x, offset.y)
        },
        onDragEnd = {
            viewModel.updatePointerDown(false)
        },
        onDrag = { offset ->
            viewModel.updatePointerDown(true)
            viewModel.updateCursorPosition(offset.x, offset.y)
        },
        onCursorShapeSelect = viewModel::updateCursorShape,
        modifier = modifier,
    )
}

@Composable
fun SimultaneousCursorsContent(
    uiState: SimultaneousCursorsUiState,
    onSizeChanged: (IntSize) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onCursorShapeSelect: (CursorShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .onSizeChanged { size ->
                onSizeChanged(size)
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onDragStart(offset)
                    },
                    onDragEnd = {
                        onDragEnd()
                    },
                    onDrag = { change, _ ->
                        onDrag(change.position)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        onDragStart(offset)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onDragEnd()
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
