package com.example.todomvc.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import com.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import com.example.todomvc.ui.todo.navigation.navigateToTodo
import com.example.todomvc.ui.todo.navigation.todoScreen

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
