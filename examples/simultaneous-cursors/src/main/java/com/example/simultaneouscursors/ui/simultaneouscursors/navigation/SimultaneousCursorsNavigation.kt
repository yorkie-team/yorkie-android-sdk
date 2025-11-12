package com.example.simultaneouscursors.ui.simultaneouscursors.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.simultaneouscursors.ui.simultaneouscursors.SimultaneousCursorsScreen
import com.example.simultaneouscursors.ui.simultaneouscursors.SimultaneousCursorsViewModel
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

const val DOCUMENT_KEY_ARG = "documentKey"
const val SIMULTANEOUS_CURSORS_BASE_ROUTE = "simultaneous_cursors_route"
const val SIMULTANEOUS_CURSORS_ROUTE =
    "$SIMULTANEOUS_CURSORS_BASE_ROUTE?$DOCUMENT_KEY_ARG={$DOCUMENT_KEY_ARG}"

fun NavController.navigateToSimultaneousCursors(documentKey: String) {
    val encodedDocumentKey = URLEncoder.encode(documentKey, UTF_8.name())
    val route = "$SIMULTANEOUS_CURSORS_BASE_ROUTE?${DOCUMENT_KEY_ARG}=$encodedDocumentKey"
    navigate(route = route)
}

fun NavGraphBuilder.simultaneousCursorsScreen() {
    composable(
        route = SIMULTANEOUS_CURSORS_ROUTE,
        arguments = listOf(
            navArgument(DOCUMENT_KEY_ARG) {
                defaultValue = null
                nullable = true
                type = NavType.StringType
            },
        ),
    ) { backStackEntry ->
        val documentKey = backStackEntry.arguments?.getString(DOCUMENT_KEY_ARG)

        val viewModel: SimultaneousCursorsViewModel = viewModel(
            factory = SimultaneousCursorsViewModel.provideFactory(documentKey),
        )

        SimultaneousCursorsScreen(
            viewModel = viewModel,
        )
    }
}
