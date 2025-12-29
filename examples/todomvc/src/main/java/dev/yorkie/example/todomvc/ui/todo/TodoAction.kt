package dev.yorkie.example.todomvc.ui.todo

/**
 * Sealed class representing all possible actions that can be performed on todos.
 * This follows the same pattern as the React TodoMVC example.
 */
sealed class TodoAction {
    data class AddTodo(val text: String) : TodoAction()
    data class DeleteTodo(val id: String) : TodoAction()
    data class EditTodo(val id: String, val text: String) : TodoAction()
    data class CompleteTodo(val id: String) : TodoAction()
    object ClearCompleted : TodoAction()
    object ToggleAll : TodoAction()
    data class SetFilter(val filter: TodoFilter) : TodoAction()
}
