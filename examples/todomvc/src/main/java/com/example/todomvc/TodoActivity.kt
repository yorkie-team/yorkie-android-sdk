package com.example.todomvc

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
import com.example.todomvc.ui.TodoApp
import com.example.todomvc.ui.theme.TodoMVCTheme
import dev.yorkie.util.Logger

class TodoActivity : ComponentActivity() {
    private val viewModel: TodoViewModel by viewModels {
        viewModelFactory {
            initializer {
                TodoViewModel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TodoMVCTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    val state by viewModel.state.collectAsState()
                    TodoApp(
                        state = state,
                        onAction = viewModel::dispatch,
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
