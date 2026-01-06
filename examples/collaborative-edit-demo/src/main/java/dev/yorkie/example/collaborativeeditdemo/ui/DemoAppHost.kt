package dev.yorkie.example.collaborativeeditdemo.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.yorkie.example.collaborativeeditdemo.ui.editor.EditorScreen
import dev.yorkie.example.feature.enterdocumentkey.EnterDocumentKeyScreen

@Composable
fun DemoAppHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = "documentKeyEntry",
    ) {
        composable("documentKeyEntry") {
            EnterDocumentKeyScreen(
                onNextClick = { documentKey ->
                    navController.navigate("editor/$documentKey")
                },
            )
        }
        composable(
            route = "editor/{documentKey}",
            arguments = listOf(
                navArgument("documentKey") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val documentKey = backStackEntry.arguments?.getString("documentKey")
            EditorScreen(documentKey = documentKey)
        }
    }
}
