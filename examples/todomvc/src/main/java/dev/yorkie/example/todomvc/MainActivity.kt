package dev.yorkie.example.todomvc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.yorkie.example.todomvc.ui.TodoAppHost
import dev.yorkie.example.todomvc.ui.theme.TodoMVCTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Matches JS example behaviour (Yorkie JS PR #1108): if the launcher
        // provides a "key" extra (e.g. `adb shell am start ... --es key my-room`),
        // skip the Enter-Document-Key screen and open that document directly.
        val initialDocumentKey = intent?.getStringExtra(EXTRA_DOCUMENT_KEY)
        setContent {
            TodoMVCTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colors.background,
                ) {
                    TodoAppHost(
                        navController = rememberNavController(),
                        initialDocumentKey = initialDocumentKey,
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DOCUMENT_KEY = "key"
    }
}
