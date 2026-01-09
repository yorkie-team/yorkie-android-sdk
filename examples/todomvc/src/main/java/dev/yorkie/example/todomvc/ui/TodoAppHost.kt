package dev.yorkie.example.todomvc.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.yorkie.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import dev.yorkie.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import dev.yorkie.example.todomvc.ui.todo.navigation.navigateToTodo
import dev.yorkie.example.todomvc.ui.todo.navigation.todoScreen

@Composable
fun TodoAppHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = ENTER_DOCUMENT_KEY_ROUTE,
    ) {
        enterDocumentKeyScreen(
            onNextClick = {
                navController.navigateToTodo(it)
            },
        )

        todoScreen()
    }
}
