package dev.yorkie.example.todomvc.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.yorkie.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import dev.yorkie.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import dev.yorkie.example.todomvc.ui.todo.navigation.TODO_BASE_ROUTE
import dev.yorkie.example.todomvc.ui.todo.navigation.navigateToTodo
import dev.yorkie.example.todomvc.ui.todo.navigation.todoScreen
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

@Composable
fun TodoAppHost(navController: NavHostController, initialDocumentKey: String? = null) {
    val startDestination = if (initialDocumentKey != null) {
        "$TODO_BASE_ROUTE?documentKey=${URLEncoder.encode(initialDocumentKey, UTF_8.name())}"
    } else {
        ENTER_DOCUMENT_KEY_ROUTE
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        enterDocumentKeyScreen(
            onNextClick = {
                navController.navigateToTodo(it)
            },
        )

        todoScreen()
    }
}
