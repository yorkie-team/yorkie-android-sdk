package com.example.scheduler.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scheduler.Event

@Composable
fun EventItem(
    event: Event,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(event.text) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(
                    onClick = {
                        onEdit(editText)
                        isEditing = false
                    },
                ) {
                    Text("Save")
                }

                TextButton(
                    onClick = {
                        editText = event.text
                        isEditing = false
                    },
                ) {
                    Text("Cancel")
                }
            } else {
                Text(
                    text = event.text,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = { isEditing = true },
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit event",
                    )
                }

                IconButton(
                    onClick = onDelete,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete event",
                    )
                }
            }
        }
    }
}
