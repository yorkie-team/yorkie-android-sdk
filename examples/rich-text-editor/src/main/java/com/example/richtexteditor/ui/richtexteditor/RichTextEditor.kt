package com.example.richtexteditor.ui.richtexteditor

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import kotlin.math.roundToInt

class CustomOutputTransformation : OutputTransformation {
    var styleOperations: List<OperationInfo.StyleOpInfo> by mutableStateOf(emptyList())
    var selectionPeers: Map<ActorID, Selection?> by mutableStateOf(emptyMap())

    override fun TextFieldBuffer.transformOutput() {
        if (styleOperations.isNotEmpty()) {
            // Apply all style operations to the text
            styleOperations.forEach {
                val from = it.from
                val to = if (it.from == it.to) {
                    it.to + 1
                } else {
                    it.to
                }

                if (to in (from + 1)..length) {
                    val attributes = it.attributes
                    if (attributes["bold"]?.toBoolean() == true) {
                        addStyle(
                            spanStyle = SpanStyle(
                                fontWeight = FontWeight.Bold,
                            ),
                            start = from,
                            end = to,
                        )
                    }

                    if (attributes["italic"]?.toBoolean() == true) {
                        addStyle(
                            spanStyle = SpanStyle(
                                fontStyle = FontStyle.Italic,
                            ),
                            start = from,
                            end = to,
                        )
                    }

                    val textDecorations = ArrayList<TextDecoration>()
                    if (attributes["underline"]?.toBoolean() == true) {
                        textDecorations.add(TextDecoration.Underline)
                    }
                    if (attributes["strike"]?.toBoolean() == true) {
                        textDecorations.add(TextDecoration.LineThrough)
                    }

                    addStyle(
                        spanStyle = SpanStyle(
                            textDecoration = TextDecoration.combine(textDecorations),
                        ),
                        start = from,
                        end = to,
                    )
                }
            }
        }

        // Apply background colors for each remote cursor position
        selectionPeers.values.filterNotNull().forEach { selection ->
            val start = selection.from.coerceIn(0, length)
            val end = selection.to.coerceIn(0, length)

            if (start < end) {
                // Selection range - highlight the entire range
                addStyle(
                    spanStyle = SpanStyle(
                        background = selection.color.toColor().copy(alpha = 0.3f),
                    ),
                    start = start,
                    end = end,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RichTextEditor(
    textFieldState: TextFieldState,
    isBold: Boolean,
    isItalic: Boolean,
    isUnderline: Boolean,
    isStrikethrough: Boolean,
    styleOperations: List<OperationInfo.StyleOpInfo>,
    selectionPeers: Map<ActorID, Selection?>,
    onContentChanged: (TextFieldBuffer.ChangeList, CharSequence) -> Unit,
    onToggleBold: () -> Unit,
    onToggleItalic: () -> Unit,
    onToggleUnderline: () -> Unit,
    onToggleStrikethrough: () -> Unit,
    onClearFormatting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val inputTransformation = remember {
        InputTransformation {
            onContentChanged(changes, asCharSequence())
        }
    }

    val outputTransformation = remember {
        CustomOutputTransformation()
    }

    LaunchedEffect(styleOperations, selectionPeers) {
        outputTransformation.styleOperations = styleOperations
        outputTransformation.selectionPeers = selectionPeers
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
                    onClick = onToggleBold,
                    modifier = Modifier.background(
                        if (isBold) MaterialTheme.colors.primary else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.FormatBold,
                        contentDescription = "Bold",
                        tint = if (isBold) {
                            MaterialTheme.colors.onPrimary
                        } else {
                            MaterialTheme.colors.onSurface
                        },
                    )
                }

                // Italic button
                IconButton(
                    onClick = onToggleItalic,
                    modifier = Modifier.background(
                        if (isItalic) {
                            MaterialTheme.colors.primary
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.FormatItalic,
                        contentDescription = "Italic",
                        tint = if (isItalic) {
                            MaterialTheme.colors.onPrimary
                        } else {
                            MaterialTheme.colors.onSurface
                        },
                    )
                }

                // Underline button
                IconButton(
                    onClick = onToggleUnderline,
                    modifier = Modifier.background(
                        if (isUnderline) {
                            MaterialTheme.colors.primary
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.FormatUnderlined,
                        contentDescription = "Underline",
                        tint = if (isUnderline) {
                            MaterialTheme.colors.onPrimary
                        } else {
                            MaterialTheme.colors.onSurface
                        },
                    )
                }

                // Strikethrough button
                IconButton(
                    onClick = onToggleStrikethrough,
                    modifier = Modifier.background(
                        if (isStrikethrough) {
                            MaterialTheme.colors.primary
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(4.dp),
                    ),
                ) {
                    Icon(
                        Icons.Default.StrikethroughS,
                        contentDescription = "Strikethrough",
                        tint = if (isStrikethrough) {
                            MaterialTheme.colors.onPrimary
                        } else {
                            MaterialTheme.colors.onSurface
                        },
                    )
                }

                // Clear formatting button
                IconButton(
                    onClick = onClearFormatting,
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
                    state = textFieldState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(300.dp)
                        .background(Color.White, RoundedCornerShape(4.dp)),
                    onTextLayout = {
                        textLayoutResult = it.invoke()
                    },
                    inputTransformation = inputTransformation,
                    outputTransformation = outputTransformation,
                )

                // Cursor overlay for remote cursors when from == to
                if (textLayoutResult != null) {
                    RemoteCursorOverlay(
                        text = textFieldState.text.toString(),
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

private fun String.toColor(): Color {
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
        // Ensure index is within valid range
        val validIndex = cursorIndex.coerceIn(0, textLayoutResult.layoutInput.text.length)
        val cursorRect = textLayoutResult.getCursorRect(validIndex)

        // Check for invalid cursor rect
        if (cursorIndex != 0 && cursorRect.left == 0f && cursorRect.right == 0f) {
            null
        } else if (cursorRect.width == 0f && cursorRect.height == 0f) {
            null
        } else {
            CursorPositionInfo(
                left = cursorRect.left.roundToInt(),
                top = cursorRect.top.roundToInt(),
                bottom = cursorRect.bottom.roundToInt(),
                height = with(density) { cursorRect.height.toDp() },
            )
        }
    } catch (e: Exception) {
        null
    }
}
