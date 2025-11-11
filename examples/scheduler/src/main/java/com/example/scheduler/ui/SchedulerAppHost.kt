package com.example.scheduler.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import com.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import com.example.scheduler.ui.scheduler.navigation.navigateToScheduler
import com.example.scheduler.ui.scheduler.navigation.schedulerScreen

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
