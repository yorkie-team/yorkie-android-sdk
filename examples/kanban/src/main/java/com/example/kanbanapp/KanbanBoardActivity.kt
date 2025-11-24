package com.example.kanbanapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yorkie.core.Client
import dev.yorkie.util.Logger

@SuppressLint("VisibleForTests")
class KanbanBoardActivity : ComponentActivity() {
    private val viewModel: KanbanBoardViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = Client(
                    options = Client.Options(
                        apiKey = BuildConfig.YORKIE_API_KEY,
                    ),
                    host = BuildConfig.YORKIE_SERVER_URL,
                )
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
                val context = LocalContext.current
                val cardColumns by viewModel.list.collectAsState()
                val greetingBroadcast by viewModel.greetingBroadcast.collectAsState()
                val onNewColumnAdded: (String) -> Unit = { viewModel.addCardColumn(it) }
                val onNewCardAdded: (KanbanColumn, String) -> Unit =
                    { kanbanColumn, card -> viewModel.addCardToColumn(kanbanColumn, card) }
                val onColumnDeleted: (KanbanColumn) -> Unit = { viewModel.deleteCardColumn(it) }
                val onBroadcastMessage: () -> Unit = { viewModel.broadcastMessage() }

                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    KanbanBoard(
                        kanbanColumns = cardColumns,
                        onNewColumnAdded = onNewColumnAdded,
                        onNewCardAdded = onNewCardAdded,
                        onColumnDeleted = onColumnDeleted,
                    )

                    LaunchedEffect(greetingBroadcast) {
                        if (greetingBroadcast != null) {
                            Toast.makeText(context, greetingBroadcast, Toast.LENGTH_SHORT).show()
                        }
                    }

                    FloatingActionButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        onClick = onBroadcastMessage,
                    ) {
                        Icon(Icons.Default.Share, null)
                    }
                }
            }
        }
    }

    companion object {

        init {
            Logger.init(
                object : Logger {
                    override val minimumPriority: Int = Log.DEBUG

                    override fun d(
                        tag: String,
                        message: String?,
                        throwable: Throwable?,
                    ) {
                        Log.d(tag, message, throwable)
                    }

                    override fun e(
                        tag: String,
                        message: String?,
                        throwable: Throwable?,
                    ) {
                        Log.e(tag, message, throwable)
                    }
                },
            )
        }
    }
}
