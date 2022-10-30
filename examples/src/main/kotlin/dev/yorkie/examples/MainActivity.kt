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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.yorkie.core.Client
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
        val statusState by client.status.collectAsState()

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                enabled = statusState is Client.Status.Deactivated,
                onClick = {
                    scope.launch {
                        client.activateAsync().await()
                    }
                },
            ) {
                Text("Activate")
            }

            Button(
                enabled = statusState is Client.Status.Activated,
                onClick = {
                    scope.launch {
                        client.deactivateAsync().await()
                    }
                },
            ) {
                Text("Deactivate")
            }

            Text("${if (statusState is Client.Status.Activated) "activated" else "deactivated"}.")
        }
    }
}
