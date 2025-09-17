package com.example.texteditor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.yorkie.document.time.ActorID

/**
 * A pure Compose TextField that replaces the Android View-based YorkieEditText
 */
@Composable
fun YorkieTextField(
    content: String,
    onTextChange: (from: Int, to: Int, content: CharSequence) -> Unit,
    onSelectionChange: (from: Int, to: Int) -> Unit,
    onHangulCompositionStart: () -> Unit,
    onHangulCompositionEnd: () -> Unit,
    selectionHighlights: Map<ActorID, Pair<Int, Int>>,
    selectionColors: Map<ActorID, Color>,
    modifier: Modifier = Modifier,
) {
    YorkieComposeTextField(
        content = content,
        onTextChange = onTextChange,
        onSelectionChange = onSelectionChange,
        onHangulCompositionStart = onHangulCompositionStart,
        onHangulCompositionEnd = onHangulCompositionEnd,
        selectionHighlights = selectionHighlights,
        selectionColors = selectionColors,
        modifier = modifier
    )
}
