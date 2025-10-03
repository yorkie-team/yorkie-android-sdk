package com.example.scheduler

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
import com.example.scheduler.ui.SchedulerApp
import com.example.scheduler.ui.theme.SchedulerTheme
import dev.yorkie.util.Logger

class SchedulerActivity : ComponentActivity() {
    private val viewModel: SchedulerViewModel by viewModels {
        viewModelFactory {
            initializer {
                SchedulerViewModel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SchedulerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    val state by viewModel.state.collectAsState()
                    SchedulerApp(
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
                        Log.d(tag, message.orEmpty(), throwable)
                    }

                    override fun e(
                        tag: String,
                        message: String?,
                        throwable: Throwable?,
                    ) {
                        Log.e(tag, message.orEmpty(), throwable)
                    }
                },
            )
        }
    }
}
