package dev.yorkie.examples

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.yorkie.Client
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background,
            ) {
                Greeting()
            }
        }
    }

    @Composable
    fun Greeting() {
        val context = LocalContext.current
        val client = remember { Client(context, "10.0.2.2:8080", true) }
        val scope = rememberCoroutineScope()
        var activated by remember { mutableStateOf(false) }

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                enabled = !activated,
                onClick = {
                    scope.launch {
                        client.activate()
                        activated = true
                    }
                },
            ) {
                Text("Activate")
            }

            Button(
                enabled = activated,
                onClick = {
                    scope.launch {
                        client.deactivate()
                        activated = false
                    }
                },
            ) {
                Text("Deactivate")
            }

            Text(text = "Client ${if (activated) "activated" else "deactivated"}.")
        }
    }
}
