package com.example.kanbanapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

class KanbanBoardActivity : ComponentActivity() {
    private val viewModel: KanbanBoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init(this)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background,
            ) {
                val cardColumns by viewModel.list.collectAsState()
                val onNewColumnAdded: (String) -> Unit = { viewModel.addCardColumn(it) }
                val onNewCardAdded: (KanbanColumn, String) -> Unit =
                    { kanbanColumn, card -> viewModel.addCardToColumn(kanbanColumn, card) }
                val onColumnDeleted: (KanbanColumn) -> Unit = { viewModel.deleteCardColumn(it) }

                KanbanBoard(
                    kanbanColumns = cardColumns,
                    onNewColumnAdded = onNewColumnAdded,
                    onNewCardAdded = onNewCardAdded,
                    onColumnDeleted = onColumnDeleted,
                )
            }
        }
    }
}
