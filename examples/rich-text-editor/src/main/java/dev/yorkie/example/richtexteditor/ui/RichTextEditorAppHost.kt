package dev.yorkie.example.richtexteditor.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.yorkie.example.feature.enterdocumentkey.navigation.ENTER_DOCUMENT_KEY_ROUTE
import dev.yorkie.example.feature.enterdocumentkey.navigation.enterDocumentKeyScreen
import dev.yorkie.example.richtexteditor.ui.richtexteditor.navigation.navigateToRichTextEditor
import dev.yorkie.example.richtexteditor.ui.richtexteditor.navigation.richTextEditorScreen

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
