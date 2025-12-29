package dev.yorkie.example.collaborativeeditdemo.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun EditorScreen(
    documentKey: String?,
    viewModel: EditorViewModel = viewModel(
        factory = EditorViewModel.provideFactory(documentKey),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Participants
        ParticipantsSection(uiState)

        Spacer(modifier = Modifier.height(16.dp))

        // Collaborative Editor
        if (!uiState.isLoading && uiState.error == null) {
            CollaborativeEditor(
                textFieldState = viewModel.textFieldState,
                selectionPeers = uiState.selectionPeers,
                styleOperations = uiState.styleOperations,
                isBold = uiState.isBold,
                isItalic = uiState.isItalic,
                isUnderline = uiState.isUnderline,
                isStrikethrough = uiState.isStrikethrough,
                onContentChanged = viewModel::editContent,
                onToggleBold = viewModel::toggleBold,
                onToggleItalic = viewModel::toggleItalic,
                onToggleUnderline = viewModel::toggleUnderline,
                onToggleStrikethrough = viewModel::toggleStrikethrough,
                onClearFormatting = viewModel::clearFormatting,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    // Loading overlay
    if (uiState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    // Error message
    uiState.error?.let { error ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = error,
                color = Color.Red,
                style = MaterialTheme.typography.body1,
            )
        }
    }
}

@Composable
private fun ParticipantsSection(uiState: EditorUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "Participants (${uiState.peers.size})",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.peers.isEmpty()) {
                Text(
                    text = "No other participants yet",
                    style = MaterialTheme.typography.body2,
                    color = Color.Gray,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    uiState.peers.take(6).forEach { peerName ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colors.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = peerName,
                                    tint = MaterialTheme.colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = peerName.take(8),
                                style = MaterialTheme.typography.caption,
                                fontSize = 9.sp,
                            )
                        }
                    }
                    if (uiState.peers.size > 6) {
                        Text(
                            text = "+${uiState.peers.size - 6}",
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
    }
}
