package com.example.texteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yorkie.document.time.ActorID

/**
 * A pure Compose replacement for YorkieEditText with all the collaborative editing functionality
 */
@Composable
fun YorkieComposeTextField(
    content: String,
    onTextChange: (from: Int, to: Int, content: CharSequence) -> Unit,
    onSelectionChange: (from: Int, to: Int) -> Unit,
    onHangulCompositionStart: () -> Unit,
    onHangulCompositionEnd: () -> Unit,
    selectionHighlights: Map<ActorID, Pair<Int, Int>>,
    selectionColors: Map<ActorID, Color>,
    modifier: Modifier = Modifier,
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }
    var isApplyingRemoteChange by remember { mutableStateOf(false) }
    var isComposing by remember { mutableStateOf(false) }
    var selectionFromTextChange by remember { mutableStateOf(false) }

    // Hangul character ranges
    val hangulConsonants = '\u3131'..'\u314E'
    val hangulVowels = '\u314F'..'\u3163'
    val hangulSyllables = '\uAC00'..'\uD7AF'

    // Apply remote content changes
    LaunchedEffect(content) {
        if (!isApplyingRemoteChange && textFieldValue.text != content) {
            isApplyingRemoteChange = true
            textFieldValue = textFieldValue.copy(text = content)
            isApplyingRemoteChange = false
        }
    }

    fun CharSequence.isHangulComposing(): Boolean {
        return lastOrNull() !in hangulVowels && lastOrNull() in hangulConsonants + hangulSyllables
    }

    fun handleHangulComposition(text: String) {
        if (!isComposing && text.isHangulComposing()) {
            isComposing = true
            onHangulCompositionStart()
        } else if (isComposing && !text.isHangulComposing()) {
            isComposing = false
            onHangulCompositionEnd()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SelectionContainer {
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    if (isApplyingRemoteChange) return@BasicTextField

                    val oldText = textFieldValue.text
                    val newText = newValue.text

                    // Calculate the change
                    val commonPrefixLength = oldText.commonPrefixWith(newText).length
                    val commonSuffixLength = minOf(
                        oldText.commonSuffixWith(newText).length,
                        oldText.length - commonPrefixLength,
                        newText.length - commonPrefixLength
                    )

                    val deleteStart = commonPrefixLength
                    val deleteEnd = (oldText.length - commonSuffixLength).coerceAtLeast(deleteStart)

                    // Ensure valid substring bounds
                    val insertEnd = (newText.length - commonSuffixLength).coerceAtLeast(commonPrefixLength)
                    val insertText = if (insertEnd > commonPrefixLength) {
                        newText.substring(commonPrefixLength, insertEnd)
                    } else {
                        ""
                    }

                    // Handle Hangul composition
                    if (insertText.isNotEmpty()) {
                        handleHangulComposition(insertText)
                    }

                    // Update the text field value
                    textFieldValue = newValue
                    selectionFromTextChange = true

                    // Notify the text change handler
                    if (insertText.isNotEmpty() || deleteEnd > deleteStart) {
                        onTextChange(deleteStart, deleteEnd, insertText)
                    }
                },
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                decorationBox = { innerTextField ->
                    Box {
                        // Simple indication when peer selections are active
                        if (selectionHighlights.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Color.Yellow.copy(alpha = 0.1f) // Very light yellow background when peers are selecting
                                    )
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }

    // Handle selection changes
    LaunchedEffect(textFieldValue.selection) {
        if (selectionFromTextChange) {
            selectionFromTextChange = false
            return@LaunchedEffect
        }

        onSelectionChange(
            textFieldValue.selection.start,
            textFieldValue.selection.end
        )
    }
}

/**
 * Extension function to apply remote changes safely
 */
@Composable
fun rememberTextFieldState(
    initialContent: String = ""
): Pair<TextFieldValue, (TextFieldValue) -> Unit> {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(initialContent)) }

    val updateTextFieldValue: (TextFieldValue) -> Unit = { newValue ->
        textFieldValue = newValue
    }

    return textFieldValue to updateTextFieldValue
}
