package dev.yorkie.example.feature.enterdocumentkey.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.yorkie.example.feature.enterdocumentkey.EnterDocumentKeyScreen

const val ENTER_DOCUMENT_KEY_ROUTE = "enter_document_key_route"

fun NavGraphBuilder.enterDocumentKeyScreen(onNextClick: (String) -> Unit) {
    composable(
        route = ENTER_DOCUMENT_KEY_ROUTE,
    ) {
        EnterDocumentKeyScreen(
            onNextClick = onNextClick,
        )
    }
}
