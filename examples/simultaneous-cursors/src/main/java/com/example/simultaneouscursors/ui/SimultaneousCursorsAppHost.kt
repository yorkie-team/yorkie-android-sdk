package com.example.simultaneouscursors.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import com.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import com.example.simultaneouscursors.ui.simultaneouscursors.navigation.navigateToSimultaneousCursors
import com.example.simultaneouscursors.ui.simultaneouscursors.navigation.simultaneousCursorsScreen

@Composable
fun SimultaneousCursorsAppHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = ENTER_DOCUMENT_KEY_ROUTE,
    ) {
        enterDocumentKeyScreen(
            onNextClick = {
                navController.navigateToSimultaneousCursors(it)
            },
        )

        simultaneousCursorsScreen()
    }
}
