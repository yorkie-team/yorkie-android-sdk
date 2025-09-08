package com.example.simultaneouscursors

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.simultaneouscursors.ui.SimultaneousCursorsApp
import com.example.simultaneouscursors.ui.theme.SimultaneousCursorsTheme
import com.example.simultaneouscursors.viewmodel.SimultaneousCursorsViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SimultaneousCursorsViewModel by viewModels {
        viewModelFactory {
            initializer {
                SimultaneousCursorsViewModel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimultaneousCursorsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    SimultaneousCursorsApp(viewModel = viewModel)
                }
            }
        }
    }
}
