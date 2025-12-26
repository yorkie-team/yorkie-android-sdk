package dev.yorkie.example.richtexteditor.ui.richtexteditor.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.yorkie.example.richtexteditor.ui.richtexteditor.EditorViewModel
import dev.yorkie.example.richtexteditor.ui.richtexteditor.RichTextEditorScreen
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

const val DOCUMENT_KEY_ARG = "documentKey"
const val RICH_TEXT_EDITOR_BASE_ROUTE = "rich_text_editor_route"
const val RICH_TEXT_EDITOR_ROUTE = "rich_text_editor_route?$DOCUMENT_KEY_ARG={$DOCUMENT_KEY_ARG}"

fun NavController.navigateToRichTextEditor(documentKey: String) {
    val encodedDocumentKey = URLEncoder.encode(documentKey, UTF_8.name())
    val route = "$RICH_TEXT_EDITOR_BASE_ROUTE?${DOCUMENT_KEY_ARG}=$encodedDocumentKey"
    navigate(route = route)
}

fun NavGraphBuilder.richTextEditorScreen() {
    composable(
        route = RICH_TEXT_EDITOR_ROUTE,
        arguments = listOf(
            navArgument(DOCUMENT_KEY_ARG) {
                defaultValue = null
                nullable = true
                type = NavType.StringType
            },
        ),
    ) { backStackEntry ->
        val documentKey = backStackEntry.arguments?.getString(DOCUMENT_KEY_ARG)

        val viewModel: EditorViewModel = viewModel(
            factory = EditorViewModel.provideFactory(documentKey),
        )

        RichTextEditorScreen(
            viewModel = viewModel,
        )
    }
}
