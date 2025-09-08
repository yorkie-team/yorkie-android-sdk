package com.example.simultaneouscursors.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.example.simultaneouscursors.ui.components.AnimatedCursor
import com.example.simultaneouscursors.ui.components.CursorSelections
import com.example.simultaneouscursors.viewmodel.SimultaneousCursorsViewModel

@Composable
fun SimultaneousCursorsApp(
    modifier: Modifier = Modifier,
    viewModel: SimultaneousCursorsViewModel = SimultaneousCursorsViewModel(),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .onSizeChanged { size ->
                // Update screen dimensions in ViewModel when size changes
                viewModel.updateScreenDimensions(
                    size.width.toFloat(),
                    size.height.toFloat(),
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        viewModel.updatePointerDown(true)
                        viewModel.updateCursorPosition(offset.x, offset.y)
                    },
                    onDragEnd = {
                        viewModel.updatePointerDown(false)
                    },
                    onDrag = { change, _ ->
                        viewModel.updateCursorPosition(change.position.x, change.position.y)
                    }
                )
            }
    ) {
        // Render all client cursors
        viewModel.clients.forEach { clientPresence ->
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
            selectedCursorShape = viewModel.selectedCursorShape,
            clientsCount = viewModel.clients.size,
            onCursorShapeSelect = viewModel::updateCursorShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}
