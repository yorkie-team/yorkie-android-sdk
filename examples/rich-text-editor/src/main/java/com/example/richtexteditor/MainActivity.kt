package com.example.richtexteditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.richtexteditor.ui.components.RichTextEditorScreen
import com.example.richtexteditor.ui.theme.RichTextEditorTheme
import com.example.richtexteditor.ui.viewmodel.EditorViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                EditorViewModel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RichTextEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    RichTextEditorApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun RichTextEditorApp(viewModel: EditorViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    RichTextEditorScreen(
        isLoading = uiState.isLoading,
        error = uiState.error,
        peers = uiState.peers,
        selectionPeers = uiState.selectionPeers,
        textFieldState = viewModel.textFieldState,
        isBold = uiState.isBold,
        isItalic = uiState.isItalic,
        isUnderline = uiState.isUnderline,
        isStrikethrough = uiState.isStrikethrough,
        styleOperations = uiState.styleOperations,
        onContentChanged = viewModel::editContent,
        onToggleBold = viewModel::toggleBold,
        onToggleItalic = viewModel::toggleItalic,
        onToggleUnderline = viewModel::toggleUnderline,
        onToggleStrikethrough = viewModel::toggleStrikethrough,
        onClearFormatting = viewModel::clearFormatting,
    )
}
