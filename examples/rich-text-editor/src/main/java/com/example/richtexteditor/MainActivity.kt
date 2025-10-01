package com.example.richtexteditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.richtexteditor.ui.theme.RichTextEditorTheme
import com.example.richtexteditor.ui.components.RichTextEditorScreen
import com.example.richtexteditor.ui.viewmodel.EditorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RichTextEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    RichTextEditorApp()
                }
            }
        }
    }
}

@Composable
fun RichTextEditorApp(
    viewModel: EditorViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    RichTextEditorScreen(
        isLoading = uiState.isLoading,
        error = uiState.error,
        peers = uiState.peers,
        selectionPeers = uiState.selectionPeers,
        content = uiState.content,
        editOperations = uiState.editOperations,
        onEditEvent = viewModel::handleEditEvent,
        onClearOperations = viewModel::clearEditOperations,
        onTextSelected = viewModel::handleSelectEvent,
    )
}
