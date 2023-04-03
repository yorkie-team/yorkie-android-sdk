package com.example.kanbanapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yorkie.core.Client
import dev.yorkie.util.Logger
import dev.yorkie.util.YorkieLogger

class KanbanBoardActivity : ComponentActivity() {
    private val viewModel: KanbanBoardViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = Client(this@KanbanBoardActivity, "api.yorkie.dev", 443)
                KanbanBoardViewModel(client)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    companion object {

        init {
            YorkieLogger.logger = object : Logger {

                override fun d(tag: String, message: String) {
                    Log.d(tag, message)
                }

                override fun e(tag: String, message: String) {
                    Log.e(tag, message)
                }
            }
        }
    }
}
