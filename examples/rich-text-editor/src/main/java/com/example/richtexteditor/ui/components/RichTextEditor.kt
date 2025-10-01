package com.example.richtexteditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.example.richtexteditor.ui.viewmodel.Selection
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import kotlin.math.roundToInt

@Composable
fun RichTextEditor(
    content: String,
    editOperations: List<OperationInfo.EditOpInfo>,
    selectionPeers: Map<ActorID, Selection?>,
    onEditEvent: (from: Int, to: Int, content: String) -> Unit,
    onClearOperations: () -> Unit,
    onTextSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(TextFieldValue(text = "")) }
    var isBold by remember { mutableStateOf(false) }
    var isItalic by remember { mutableStateOf(false) }
    var isUnderline by remember { mutableStateOf(false) }
    var isStrikethrough by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(text.selection) {
        onTextSelected(text.selection.start, text.selection.end)
    }

    // Handle remote content changes (initial sync)
    LaunchedEffect(content) {
        if (content != text.text) {
            // Preserve cursor position if possible
            val cursorPosition = text.selection.start.coerceIn(0, content.length)
            text = TextFieldValue(
                text = content,
                selection = TextRange(cursorPosition),
            )
        }
    }

    // Handle remote edit operations with proper cursor preservation
    LaunchedEffect(editOperations) {
        if (editOperations.isNotEmpty()) {
            editOperations.forEach { opInfo ->
                // Apply remote changes to local text with bounds checking
                val currentText = text.text
                val textLength = currentText.length
                val currentCursor = text.selection.start

                // Ensure indices are within bounds
                val from = opInfo.from.coerceIn(0, textLength)
                val to = opInfo.to.coerceIn(0, textLength)

                val (newText, newCursor) = if (from == to) {
                    // Insert operation
                    val insertedText = opInfo.value.text
                    val resultText =
                        currentText.substring(0, from) + insertedText + currentText.substring(from)

                    // Calculate new cursor position
                    val resultCursor = when {
                        currentCursor < from -> currentCursor // Cursor before insert point
                        currentCursor == from -> from + insertedText.length // Cursor at insert point, move after insert
                        else -> currentCursor + insertedText.length // Cursor after insert point, shift right
                    }
                    resultText to resultCursor
                } else {
                    // Replace/delete operation
                    val insertedText = opInfo.value.text
                    val deletedLength = to - from
                    val resultText =
                        currentText.substring(0, from) + insertedText + currentText.substring(to)

                    // Calculate new cursor position
                    val resultCursor = when {
                        currentCursor <= from -> currentCursor // Cursor before change
                        currentCursor > to -> currentCursor - deletedLength + insertedText.length // Cursor after change
                        else -> from + insertedText.length // Cursor was inside deleted range, move to end of insert
                    }
                    resultText to resultCursor
                }

                text = TextFieldValue(
                    text = newText,
                    selection = TextRange(newCursor.coerceIn(0, newText.length)),
                )
            }

            // Clear operations after processing
            onClearOperations()
        }
    }

    Column(
        modifier = modifier,
    ) {
        // Toolbar
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colors.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // Bold button
                IconButton(
                    onClick = { isBold = !isBold },
                    modifier = Modifier.background(
                        if (isBold) MaterialTheme.colors.primary else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.FormatBold,
                        contentDescription = "Bold",
                        tint = if (isBold) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                    )
                }

                // Italic button
                IconButton(
                    onClick = { isItalic = !isItalic },
                    modifier = Modifier.background(
                        if (isItalic) MaterialTheme.colors.primary else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.FormatItalic,
                        contentDescription = "Italic",
                        tint = if (isItalic) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                    )
                }

                // Underline button
                IconButton(
                    onClick = { isUnderline = !isUnderline },
                    modifier = Modifier.background(
                        if (isUnderline) MaterialTheme.colors.primary else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.FormatUnderlined,
                        contentDescription = "Underline",
                        tint = if (isUnderline) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                    )
                }

                // Strikethrough button
                IconButton(
                    onClick = { isStrikethrough = !isStrikethrough },
                    modifier = Modifier.background(
                        if (isStrikethrough) MaterialTheme.colors.primary else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.StrikethroughS,
                        contentDescription = "Strikethrough",
                        tint = if (isStrikethrough) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                    )
                }

                // Clear formatting button
                IconButton(
                    onClick = {
                        isBold = false
                        isItalic = false
                        isUnderline = false
                        isStrikethrough = false
                    },
                ) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear Formatting",
                        tint = MaterialTheme.colors.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Text Editor with Remote Selections Display
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colors.surface,
        ) {
            Box {
                // Text field with embedded remote cursor highlights
                BasicTextField(
                    value = TextFieldValue(
                        annotatedString = buildAnnotatedStringWithCursors(
                            text.text,
                            selectionPeers,
                        ),
                        selection = text.selection,
                    ),
                    onValueChange = { newValue ->
                        val oldText = text.text
                        val newText = newValue.text

                        if (oldText != newText) {
                            // Calculate the actual changed range for efficient updates
                            var from = 0

                            // Find the start of the change
                            while (from < oldText.length && from < newText.length &&
                                oldText[from] == newText[from]
                            ) {
                                from++
                            }

                            // Find the end of the change
                            var oldEnd = oldText.length
                            var newEnd = newText.length
                            while (oldEnd > from && newEnd > from &&
                                oldText[oldEnd - 1] == newText[newEnd - 1]
                            ) {
                                oldEnd--
                                newEnd--
                            }
                            val to = oldEnd

                            val changedContent = newText.substring(from, newEnd)

                            onEditEvent(
                                from,
                                to,
                                changedContent,
                            )
                        }
                        text = newValue
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(300.dp)
                        .background(Color.White, RoundedCornerShape(4.dp)),
                    textStyle = MaterialTheme.typography.body1.copy(
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colors.onSurface,
                    ),
                    decorationBox = @Composable { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (text.text.isEmpty()) {
                                Text(
                                    text = "Start typing...",
                                    style = MaterialTheme.typography.body1,
                                    color = Color.Gray.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        }
                    },
                    onTextLayout = {
                        textLayoutResult = it
                    },
                )

                // Cursor overlay for remote cursors when from == to
                if (textLayoutResult != null) {
                    RemoteCursorOverlay(
                        text = text.text,
                        selectionPeers = selectionPeers,
                        textLayoutResult = textLayoutResult!!,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Builds an AnnotatedString with background colors for remote cursor positions
 */
private fun buildAnnotatedStringWithCursors(
    text: String,
    peers: Map<ActorID, Selection?>,
): AnnotatedString {
    return buildAnnotatedString {
        append(text)

        // Apply background colors for each remote cursor position
        peers.values.filterNotNull().forEach { selection ->
            val start = selection.from.coerceIn(0, text.length)
            val end = selection.to.coerceIn(0, text.length)

            if (start < end) {
                // Selection range - highlight the entire range
                addStyle(
                    style = SpanStyle(
                        background = selection.color.toColor().copy(alpha = 0.3f),
                    ),
                    start = start,
                    end = end,
                )
            }
        }
    }
}

fun String.toColor(): Color {
    return Color(this.toColorInt())
}


/**
 * Overlay component that shows remote cursors when from == to (cursor position)
 */
@Composable
private fun RemoteCursorOverlay(
    text: String,
    selectionPeers: Map<ActorID, Selection?>,
    textLayoutResult: TextLayoutResult,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Show cursor indicators for remote users when they have a cursor position (from == to)
        selectionPeers.values.filterNotNull().forEach { selection ->
            if (selection.from == selection.to) {
                // This is a cursor position, not a selection
                val cursorPosition = selection.from.coerceIn(0, text.length)

                // Calculate accurate cursor position using proper text measurement
                calculateAccurateCursorPosition(
                    cursorIndex = cursorPosition,
                    textLayoutResult = textLayoutResult,
                )?.let { cursorPositionInfo ->
                    // Position the cursor indicator with name label
                    // Cursor indicator
                    Box(
                        modifier = Modifier
                            .size(width = 2.dp, height = cursorPositionInfo.height)
                            .offset {
                                IntOffset(cursorPositionInfo.left, cursorPositionInfo.top)
                            }
                            .background(
                                color = selection.color.toColor(),
                                shape = RoundedCornerShape(1.dp),
                            ),
                    )

                    // Name label below cursor, centered horizontally
                    var textWidth by remember { mutableIntStateOf(0) }
                    Text(
                        text = selection.name,
                        modifier = Modifier
                            .onGloballyPositioned { coordinates ->
                                textWidth = coordinates.size.width
                            }
                            .offset {
                                // Center the text horizontally relative to the cursor
                                val centeredLeft = cursorPositionInfo.left - (textWidth / 2)
                                IntOffset(
                                    centeredLeft.coerceAtLeast(0),
                                    cursorPositionInfo.bottom + 4,
                                )
                            }
                            .background(
                                color = selection.color.toColor().copy(alpha = 0.8f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.caption.copy(
                            color = Color.White,
                            fontSize = 10.sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Data class to hold cursor position information
 */
private data class CursorPositionInfo(
    val left: Int,
    val top: Int,
    val bottom: Int,
    val height: Dp,
)

/**
 * Calculate cursor position using the simplest possible approach
 */
@Composable
private fun calculateAccurateCursorPosition(
    cursorIndex: Int,
    textLayoutResult: TextLayoutResult,
): CursorPositionInfo? {
    val density = LocalDensity.current

    return try {
        val cursorRect = textLayoutResult.getCursorRect(cursorIndex)

        CursorPositionInfo(
            left = cursorRect.left.roundToInt(),
            top = cursorRect.top.roundToInt(),
            bottom = cursorRect.bottom.roundToInt(),
            height = with(density) { cursorRect.height.toDp() },
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}
