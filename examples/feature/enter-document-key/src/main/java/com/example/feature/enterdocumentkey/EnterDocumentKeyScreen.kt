package com.example.feature.enterdocumentkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun EnterDocumentKeyScreen(onNextClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        var text by rememberSaveable {
            mutableStateOf("")
        }
        var error by rememberSaveable {
            mutableStateOf<EnterDocumentKeyError?>(null)
        }

        Text(
            text = stringResource(R.string.enter_document_key),
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            isError = error != null,
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (error) {
                    EnterDocumentKeyError.EXCEED_LENGTH -> {
                        stringResource(R.string.document_key_length_error)
                    }

                    else -> {
                        ""
                    }
                },
                color = Color.Red,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (text.length in 4..120) {
                    onNextClick(text)
                } else {
                    error = EnterDocumentKeyError.EXCEED_LENGTH
                }
            },
        ) {
            Text(stringResource(R.string.next))
        }
    }
}

enum class EnterDocumentKeyError {
    EXCEED_LENGTH,
}
