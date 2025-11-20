package com.example.todomvc.ui.todo

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.todomvc.R

@Composable
fun TodoItem(
    todo: Todo,
    onComplete: () -> Unit,
    onEditClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox
        Checkbox(
            checked = todo.completed,
            onCheckedChange = { onComplete() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF5DC2AF),
                uncheckedColor = Color(0xFFE6E6E6),
            ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Todo text
        Text(
            text = todo.text,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                color = if (todo.completed) {
                    colorResource(id = R.color.todo_completed_text)
                } else {
                    colorResource(id = R.color.todo_active_text)
                },
                textDecoration = if (todo.completed) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
            ),
            modifier = Modifier.weight(1f),
        )

        // Edit button
        IconButton(
            onClick = onEditClick,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = Color(0xFF8B8B8B),
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Delete button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Delete",
                tint = Color(0xFFCC9A9A),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
