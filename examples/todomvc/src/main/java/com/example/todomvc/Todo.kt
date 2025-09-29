package com.example.todomvc

/**
 * Data class representing a Todo item.
 */
data class Todo(
    val id: String,
    val text: String,
    val completed: Boolean,
)

/**
 * Enum representing different filter states for todos.
 */
enum class TodoFilter {
    ALL,
    ACTIVE,
    COMPLETED,
}

/**
 * Data class representing the complete state of the TodoMVC app.
 */
data class TodoState(
    val todos: List<Todo>,
    val filter: TodoFilter = TodoFilter.ALL,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val filteredTodos: List<Todo>
        get() = when (filter) {
            TodoFilter.ALL -> todos
            TodoFilter.ACTIVE -> todos.filter { !it.completed }
            TodoFilter.COMPLETED -> todos.filter { it.completed }
        }

    val activeCount: Int
        get() = todos.count { !it.completed }

    val completedCount: Int
        get() = todos.count { it.completed }

    val allCompleted: Boolean
        get() = todos.isNotEmpty() && todos.all { it.completed }
}
