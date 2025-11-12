package com.example.richtexteditor.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import com.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import com.example.richtexteditor.ui.richtexteditor.navigation.navigateToRichTextEditor
import com.example.richtexteditor.ui.richtexteditor.navigation.richTextEditorScreen

@Composable
fun RichTextEditorAppHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = ENTER_DOCUMENT_KEY_ROUTE,
    ) {
        enterDocumentKeyScreen(
            onNextClick = {
                navController.navigateToRichTextEditor(it)
            },
        )

        richTextEditorScreen()
    }
}
