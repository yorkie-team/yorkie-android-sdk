package com.example.texteditor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yorkie.core.Client
import dev.yorkie.util.Logger
import dev.yorkie.util.createSingleThreadDispatcher
import java.util.PriorityQueue
import okhttp3.OkHttpClient
import okhttp3.Protocol

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val unaryClient = OkHttpClient.Builder()
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .build()
                EditorViewModel(
                    Client(
                        options = Client.Options(
                            apiKey = BuildConfig.YORKIE_API_KEY,
                        ),
                        host = BuildConfig.YORKIE_SERVER_URL,
                        unaryClient = unaryClient,
                        streamClient = unaryClient,
                        dispatcher = createSingleThreadDispatcher(
                            "YorkieClient",
                        ),
                    ),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    TextEditorScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(20.dp),
                    )
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

@Composable
fun TextEditorScreen(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    // Observe MVI state
    val state by viewModel.state.collectAsState()

    // Show loading state
    if (state.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // Show error state
    state.error?.let { error ->
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Error: $error",
                color = MaterialTheme.colors.error,
            )
        }
        return
    }

    // Main text editor UI
    YorkieTextField(
        content = state.content,
        onTextChange = { from, to, content ->
            val changeRange = TextEditorContract.TextRange(from, to, content.toString())
            viewModel.handleIntent(
                TextEditorContract.Intent.TextChanged(
                    newText = content.toString(),
                    changeRange = changeRange
                )
            )
        },
        onSelectionChange = { from, to ->
            viewModel.handleIntent(TextEditorContract.Intent.SelectionChanged(from, to))
        },
        onHangulCompositionStart = {
            viewModel.handleIntent(TextEditorContract.Intent.StartHangulComposition)
        },
        onHangulCompositionEnd = {
            viewModel.handleIntent(TextEditorContract.Intent.EndHangulComposition)
        },
        selectionHighlights = state.peerSelections.mapValues { (_, selection) ->
            selection.range.first to selection.range.last
        },
        selectionColors = state.peerSelections.mapValues { (_, selection) ->
            selection.color
        },
        modifier = modifier.fillMaxSize(),
    )
}
