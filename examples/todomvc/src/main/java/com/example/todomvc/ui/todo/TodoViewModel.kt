package com.example.todomvc.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.todomvc.BuildConfig
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Key
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.util.createSingleThreadDispatcher
import java.net.URLDecoder
import java.util.UUID
import kotlin.text.Charsets.UTF_8
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import timber.log.Timber

class TodoViewModel(
    documentKey: String,
) : ViewModel() {

    private val unaryClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val client = Client(
        options = Client.Options(
            apiKey = BuildConfig.YORKIE_API_KEY,
        ),
        host = BuildConfig.YORKIE_SERVER_URL,
        unaryClient = unaryClient,
        streamClient = unaryClient,
        dispatcher = createSingleThreadDispatcher(
            "YorkieClient",
        ),
    )

    private val document = Document(Key(documentKey))

    private val _state = MutableStateFlow(
        TodoState(
            todos = persistentListOf(),
            isLoading = true,
        ),
    )
    val state: StateFlow<TodoState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            connectAndAttachDocument()

            launch {
                document.events.collect { event ->
                    when (event) {
                        is Document.Event.SyncStatusChanged.Synced -> {
                            document.getRoot().getAsOrNull<JsonArray>(DOCUMENT_TODOS_KEY)
                                ?.let(::updateTodosFromDocument)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
        }
    }

    private suspend fun connectAndAttachDocument() {
        try {
            val activateAsyncResult = client.activateAsync().await()
            if (!activateAsyncResult.isSuccess) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to connect to Yorkie server",
                )
                Timber.e(
                    "${this@TodoViewModel::class.java.name}#init: %s",
                    "Failed to connect to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${activateAsyncResult.exceptionOrNull()}",
                )
                return
            }

            val attachAsyncResult = client.attachAsync(document).await()
            if (!attachAsyncResult.isSuccess) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to attach document to Yorkie server",
                )
                Timber.e(
                    "${this@TodoViewModel::class.java.name}#init: %s",
                    "Failed to attach document ${document.key} " +
                        "to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${attachAsyncResult.exceptionOrNull()}",
                )
                return
            }

            Timber.i(
                "${this@TodoViewModel::class.java.name}#init: %s",
                "Connected to Yorkie server ${BuildConfig.YORKIE_SERVER_URL} " +
                    "with document ${document.key}",
            )

            _state.value = _state.value.copy(
                isLoading = false,
            )

            client.syncAsync().await()
            document.getRoot().getAsOrNull<JsonArray>(DOCUMENT_TODOS_KEY)
                ?: run {
                    document.updateAsync { root, _ ->
                        root.setNewArray(DOCUMENT_TODOS_KEY).apply {
                            putNewObject().apply {
                                set("id", UUID.randomUUID().toString())
                                set("text", "Yorkie JS SDK")
                                set("completed", false)
                            }
                            putNewObject().apply {
                                set("id", UUID.randomUUID().toString())
                                set("text", "Garbage collection")
                                set("completed", false)
                            }
                            putNewObject().apply {
                                set("id", UUID.randomUUID().toString())
                                set("text", "RichText datatype")
                                set("completed", false)
                            }
                        }
                    }.await()
                }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Failed to connect: ${e.message}",
            )
            Timber.e(
                "${this@TodoViewModel::class.java.name}#init: %s",
                e.message ?: "Unknown error occurred",
            )
        }
    }

    fun dispatch(action: TodoAction) {
        when (action) {
            is TodoAction.AddTodo -> addTodo(action.text)
            is TodoAction.DeleteTodo -> deleteTodo(action.id)
            is TodoAction.EditTodo -> editTodo(action.id, action.text)
            is TodoAction.CompleteTodo -> completeTodo(action.id)
            is TodoAction.ClearCompleted -> clearCompleted()
            is TodoAction.ToggleAll -> toggleAll()
            is TodoAction.SetFilter -> setFilter(action.filter)
        }
    }

    private fun addTodo(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            document.updateAsync { root, _ ->
                root.getAs<JsonArray>(DOCUMENT_TODOS_KEY).putNewObject().apply {
                    set("id", UUID.randomUUID().toString())
                    set("text", text.trim())
                    set("completed", false)
                }
            }.await()
        }
    }

    private fun deleteTodo(id: String) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val todos = root.getAs<JsonArray>(DOCUMENT_TODOS_KEY)
                val todoToRemove = todos.filterIsInstance<JsonObject>()
                    .find { it.getAs<JsonPrimitive>("id").getValueAs<String>() == id }
                todoToRemove?.let { todos.remove(it.id) }
            }.await()
        }
    }

    private fun editTodo(id: String, text: String) {
        if (text.isBlank()) {
            deleteTodo(id)
            return
        }

        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val todos = root.getAs<JsonArray>(DOCUMENT_TODOS_KEY)
                todos.filterIsInstance<JsonObject>()
                    .find { it.getAs<JsonPrimitive>("id").getValueAs<String>() == id }
                    ?.set("text", text.trim())
            }.await()
        }
    }

    private fun completeTodo(id: String) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val todos = root.getAs<JsonArray>(DOCUMENT_TODOS_KEY)
                todos.filterIsInstance<JsonObject>()
                    .find { it.getAs<JsonPrimitive>("id").getValueAs<String>() == id }
                    ?.let { todo ->
                        val currentCompleted =
                            todo.getAs<JsonPrimitive>("completed").getValueAs<Boolean>()
                        todo["completed"] = !currentCompleted
                    }
            }.await()
        }
    }

    private fun clearCompleted() {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val todos = root.getAs<JsonArray>(DOCUMENT_TODOS_KEY)
                val completedTodos = todos.filterIsInstance<JsonObject>()
                    .filter { it.getAs<JsonPrimitive>("completed").getValueAs<Boolean>() }
                completedTodos.forEach { todos.remove(it.id) }
            }.await()
        }
    }

    private fun toggleAll() {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val todos = root.getAs<JsonArray>(DOCUMENT_TODOS_KEY)
                val todoObjects = todos.filterIsInstance<JsonObject>()
                val allCompleted = todoObjects.all {
                    it.getAs<JsonPrimitive>("completed").getValueAs<Boolean>()
                }
                todoObjects.forEach { todo ->
                    todo["completed"] = !allCompleted
                }
            }.await()
        }
    }

    private fun setFilter(filter: TodoFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    private fun updateTodosFromDocument(todosArray: JsonArray) {
        viewModelScope.launch {
            val todos = todosArray.filterIsInstance<JsonObject>()
                .mapNotNull { todoObj ->
                    val id = todoObj.getAsOrNull<JsonPrimitive>("id")
                    if (id != null) {
                        Todo(
                            id = todoObj.getAs<JsonPrimitive>("id").getValueAs(),
                            text = todoObj.getAs<JsonPrimitive>("text").getValueAs(),
                            completed = todoObj.getAs<JsonPrimitive>("completed").getValueAs(),
                        )
                    } else {
                        null
                    }
                }

            _state.value = _state.value.copy(
                todos = todos,
                isLoading = false,
                error = null,
            )
        }
    }

    override fun onCleared() {
        TerminationScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
    }

    companion object {
        private const val DOCUMENT_TODOS_KEY = "todos"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

        fun provideFactory(documentKey: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    documentKey ?: throw IllegalArgumentException("Document Key is not found")
                    val decodedDocumentKey = URLDecoder.decode(documentKey, UTF_8.name())
                    return TodoViewModel(decodedDocumentKey) as T
                }
            }
    }
}
