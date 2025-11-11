package com.example.todomvc.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.todomvc.R

@Composable
fun TodoScreen(modifier: Modifier = Modifier, viewModel: TodoViewModel) {
    val state = viewModel.state.collectAsState()
    TodoScreen(
        state = state.value,
        onAction = viewModel::dispatch,
        modifier = modifier,
    )
}

@Composable
fun TodoScreen(
    state: TodoState,
    onAction: (TodoAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFilterDropdown by remember { mutableStateOf(false) }
    var showAddTodoDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.todo_background)),
    ) {
        Text(
            text = "todos",
            fontSize = 100.sp,
            fontWeight = FontWeight.Thin,
            color = Color(0xFFAF2F2F).copy(alpha = 0.15f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )

        // Filter and Add Todo section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Filter button
                Card(
                    modifier = Modifier.weight(1f),
                    elevation = 2.dp,
                    shape = RoundedCornerShape(8.dp),
                    backgroundColor = MaterialTheme.colors.surface,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFilterDropdown = !showFilterDropdown },
                        color = if (showFilterDropdown) {
                            MaterialTheme.colors.primary.copy(alpha = 0.08f)
                        } else {
                            Color.Transparent
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FilterList,
                                    contentDescription = "Filter tasks",
                                    tint = MaterialTheme.colors.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Filter: ${
                                        when (state.filter) {
                                            TodoFilter.ALL -> {
                                                stringResource(id = R.string.all_filter)
                                            }

                                            TodoFilter.ACTIVE -> {
                                                stringResource(id = R.string.active_filter)
                                            }

                                            TodoFilter.COMPLETED -> {
                                                stringResource(id = R.string.completed_filter)
                                            }
                                        }
                                    }",
                                    style = MaterialTheme.typography.body1,
                                    color = MaterialTheme.colors.onSurface,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colors.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                // Add Todo button
                FloatingActionButton(
                    onClick = { showAddTodoDialog = true },
                    backgroundColor = MaterialTheme.colors.primary,
                    contentColor = MaterialTheme.colors.onPrimary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Todo",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // DropdownMenu
            DropdownMenu(
                expanded = showFilterDropdown,
                onDismissRequest = { showFilterDropdown = false },
                modifier = Modifier.background(MaterialTheme.colors.surface),
            ) {
                DropdownMenuItem(
                    onClick = {
                        onAction(TodoAction.SetFilter(TodoFilter.ALL))
                        showFilterDropdown = false
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = if (state.filter == TodoFilter.ALL) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(id = R.string.all_filter),
                            style = MaterialTheme.typography.body1,
                            color = if (state.filter == TodoFilter.ALL) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface
                            },
                            fontWeight = if (state.filter == TodoFilter.ALL) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (state.filter == TodoFilter.ALL) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                DropdownMenuItem(
                    onClick = {
                        onAction(TodoAction.SetFilter(TodoFilter.ACTIVE))
                        showFilterDropdown = false
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            tint = if (state.filter == TodoFilter.ACTIVE) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(id = R.string.active_filter),
                            style = MaterialTheme.typography.body1,
                            color = if (state.filter == TodoFilter.ACTIVE) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface
                            },
                            fontWeight = if (state.filter == TodoFilter.ACTIVE) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (state.filter == TodoFilter.ACTIVE) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                DropdownMenuItem(
                    onClick = {
                        onAction(TodoAction.SetFilter(TodoFilter.COMPLETED))
                        showFilterDropdown = false
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (state.filter == TodoFilter.COMPLETED) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(id = R.string.completed_filter),
                            style = MaterialTheme.typography.body1,
                            color = if (state.filter == TodoFilter.COMPLETED) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurface
                            },
                            fontWeight = if (state.filter == TodoFilter.COMPLETED) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (state.filter == TodoFilter.COMPLETED) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Error: ${state.error}",
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            // Main content
            if (state.todos.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    elevation = 2.dp,
                ) {
                    Column {
                        // Toggle all section
                        TodoToggleAll(
                            allCompleted = state.allCompleted,
                            onToggleAll = { onAction(TodoAction.ToggleAll) },
                        )

                        // Todo list
                        LazyColumn {
                            items(
                                items = state.filteredTodos,
                                key = { it.id },
                            ) { todo ->
                                TodoItem(
                                    todo = todo,
                                    onComplete = { onAction(TodoAction.CompleteTodo(todo.id)) },
                                    onEdit = { text ->
                                        onAction(
                                            TodoAction.EditTodo(
                                                todo.id,
                                                text,
                                            ),
                                        )
                                    },
                                    onDelete = { onAction(TodoAction.DeleteTodo(todo.id)) },
                                )
                            }
                        }

                        // Footer
                        TodoFooter(
                            activeCount = state.activeCount,
                            completedCount = state.completedCount,
                            onClearCompleted = { onAction(TodoAction.ClearCompleted) },
                        )
                    }
                }
            }
        }

        // Add Todo Dialog
        if (showAddTodoDialog) {
            AddTodoDialog(
                onAddTodo = { text ->
                    onAction(TodoAction.AddTodo(text))
                    showAddTodoDialog = false
                },
                onDismiss = { showAddTodoDialog = false },
            )
        }
    }
}

@Composable
private fun AddTodoDialog(onAddTodo: (String) -> Unit, onDismiss: () -> Unit) {
    var todoText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add New Todo",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            OutlinedTextField(
                value = todoText,
                onValueChange = { todoText = it },
                label = { Text("What needs to be done?") },
                placeholder = { Text("Enter your todo item...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (todoText.isNotBlank()) {
                        onAddTodo(todoText.trim())
                    }
                },
                enabled = todoText.isNotBlank(),
            ) {
                Text(
                    text = "Add",
                    fontWeight = FontWeight.Bold,
                    color = if (todoText.isNotBlank()) {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface,
    )
}
