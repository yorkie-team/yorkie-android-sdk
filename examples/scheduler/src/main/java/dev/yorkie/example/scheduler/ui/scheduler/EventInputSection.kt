package dev.yorkie.example.scheduler.ui.scheduler

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yorkie.example.scheduler.R

@Composable
fun EventInputSection(
    eventText: String,
    onTextChange: (String) -> Unit,
    onAddEvent: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.add_event),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = eventText,
                onValueChange = onTextChange,
                label = { Text(stringResource(R.string.event_text_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAddEvent,
                enabled = eventText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_event))
            }
        }
    }
}
