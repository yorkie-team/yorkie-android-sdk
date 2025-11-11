package com.example.scheduler.ui.scheduler.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.scheduler.ui.scheduler.SchedulerScreen
import com.example.scheduler.ui.scheduler.SchedulerViewModel
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

const val DOCUMENT_KEY_ARG = "documentKey"
const val SCHEDULER_BASE_ROUTE = "scheduler_route"
const val SCHEDULER_ROUTE = "scheduler_route?$DOCUMENT_KEY_ARG={$DOCUMENT_KEY_ARG}"

fun NavController.navigateToScheduler(documentKey: String) {
    val encodedDocumentKey = URLEncoder.encode(documentKey, UTF_8.name())
    val route = "$SCHEDULER_BASE_ROUTE?${DOCUMENT_KEY_ARG}=$encodedDocumentKey"
    navigate(route = route)
}

fun NavGraphBuilder.schedulerScreen() {
    composable(
        route = SCHEDULER_ROUTE,
        arguments = listOf(
            navArgument(DOCUMENT_KEY_ARG) {
                defaultValue = null
                nullable = true
                type = NavType.StringType
            },
        ),
    ) { backStackEntry ->
        val documentKey = backStackEntry.arguments?.getString(DOCUMENT_KEY_ARG)

        val viewModel: SchedulerViewModel = viewModel(
            factory = SchedulerViewModel.provideFactory(documentKey),
        )

        SchedulerScreen(
            viewModel = viewModel,
        )
    }
}
