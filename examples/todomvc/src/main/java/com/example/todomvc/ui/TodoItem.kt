package com.example.todomvc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.todomvc.R
import com.example.todomvc.Todo

@Composable
fun TodoItem(
    todo: Todo,
    onComplete: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(todo.text) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            editText = todo.text
            focusRequester.requestFocus()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1.dp, vertical = 0.5.dp),
        elevation = 0.dp,
        backgroundColor = colorResource(id = R.color.todo_item_background),
    ) {
        if (isEditing) {
            // Edit mode
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                textStyle = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (editText.isNotBlank()) {
                            onEdit(editText.trim())
                        } else {
                            onDelete()
                        }
                        isEditing = false
                    },
                ),
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    backgroundColor = Color.White,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            // View mode
            Row(
                modifier = Modifier
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
                        fontSize = 24.sp,
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
                    modifier = Modifier
                        .weight(1f)
                        .clickable { isEditing = true },
                )

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
    }
}
