package com.example.richtexteditor.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.richtexteditor.ui.viewmodel.Selection
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID

@Composable
fun RichTextEditorScreen(
    isLoading: Boolean,
    error: String?,
    content: String,
    textSelection: Pair<Int, Int>,
    isBold: Boolean,
    isItalic: Boolean,
    isUnderline: Boolean,
    isStrikethrough: Boolean,
    styleOperations: List<OperationInfo.StyleOpInfo>,
    peers: List<String>,
    selectionPeers: Map<ActorID, Selection?>,
    onValueChanged: (String) -> Unit,
    onEditEvent: (from: Int, to: Int, content: String) -> Unit,
    onTextSelected: (Int, Int) -> Unit,
    onToggleBold: () -> Unit,
    onToggleItalic: () -> Unit,
    onToggleUnderline: () -> Unit,
    onToggleStrikethrough: () -> Unit,
    onClearFormatting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Participants section with cursor positions
        ParticipantsSection(
            peers = peers,
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading editor...")
                    }
                }
            }

            error != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colors.error,
                ) {
                    Text(
                        text = "Error: $error",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colors.onError,
                    )
                }
            }

            else -> {
                // Rich Text Editor
                RichTextEditor(
                    content = content,
                    textSelection = textSelection,
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    isStrikethrough = isStrikethrough,
                    styleOperations = styleOperations,
                    selectionPeers = selectionPeers,
                    onValueChanged = onValueChanged,
                    onEditEvent = onEditEvent,
                    onTextSelected = onTextSelected,
                    onToggleBold = onToggleBold,
                    onToggleItalic = onToggleItalic,
                    onToggleUnderline = onToggleUnderline,
                    onToggleStrikethrough = onToggleStrikethrough,
                    onClearFormatting = onClearFormatting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                )
            }
        }
    }
}

@Composable
private fun ParticipantsSection(peers: List<String>, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = "Participants:",
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "[${peers.joinToString()}]",
        )
    }
}
