package dev.yorkie.example.scheduler.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.yorkie.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import dev.yorkie.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import dev.yorkie.example.scheduler.ui.scheduler.navigation.navigateToScheduler
import dev.yorkie.example.scheduler.ui.scheduler.navigation.schedulerScreen

@Composable
fun SchedulerAppHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = ENTER_DOCUMENT_KEY_ROUTE,
    ) {
        enterDocumentKeyScreen(
            onNextClick = {
                navController.navigateToScheduler(it)
            },
        )

        schedulerScreen()
    }
}
