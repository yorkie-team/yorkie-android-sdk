package dev.yorkie.example.todomvc.ui.todo.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.yorkie.example.todomvc.ui.todo.TodoScreen
import dev.yorkie.example.todomvc.ui.todo.TodoViewModel
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

const val DOCUMENT_KEY_ARG = "documentKey"
const val TODO_BASE_ROUTE = "todo_route"
const val TODO_ROUTE = "todo_route?$DOCUMENT_KEY_ARG={$DOCUMENT_KEY_ARG}"

fun NavController.navigateToTodo(documentKey: String) {
    val encodedDocumentKey = URLEncoder.encode(documentKey, UTF_8.name())
    val route = "$TODO_BASE_ROUTE?${DOCUMENT_KEY_ARG}=$encodedDocumentKey"
    navigate(route = route)
}

fun NavGraphBuilder.todoScreen() {
    composable(
        route = TODO_ROUTE,
        arguments = listOf(
            navArgument(DOCUMENT_KEY_ARG) {
                defaultValue = null
                nullable = true
                type = NavType.StringType
            },
        ),
    ) { backStackEntry ->
        val documentKey = backStackEntry.arguments?.getString(DOCUMENT_KEY_ARG)

        val viewModel: TodoViewModel = viewModel(
            factory = TodoViewModel.provideFactory(documentKey),
        )

        TodoScreen(
            viewModel = viewModel,
        )
    }
}
