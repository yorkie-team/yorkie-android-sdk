package dev.yorkie.example.scheduler.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.yorkie.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import dev.yorkie.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import dev.yorkie.example.scheduler.ui.scheduler.navigation.SCHEDULER_BASE_ROUTE
import dev.yorkie.example.scheduler.ui.scheduler.navigation.navigateToScheduler
import dev.yorkie.example.scheduler.ui.scheduler.navigation.schedulerScreen
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

@Composable
fun SchedulerAppHost(navController: NavHostController, initialDocumentKey: String? = null) {
    val startDestination = if (initialDocumentKey != null) {
        "$SCHEDULER_BASE_ROUTE?documentKey=${URLEncoder.encode(initialDocumentKey, UTF_8.name())}"
    } else {
        ENTER_DOCUMENT_KEY_ROUTE
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        enterDocumentKeyScreen(
            onNextClick = {
                navController.navigateToScheduler(it)
            },
        )

        schedulerScreen()
    }
}
