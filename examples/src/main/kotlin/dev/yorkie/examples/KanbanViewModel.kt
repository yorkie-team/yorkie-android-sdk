package dev.yorkie.examples

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KanbanViewModel : ViewModel() {
    lateinit var client: Client
        private set
    private val document = Document(Document.Key(DOCUMENT_KEY))

    private val defaultKanbanList = mutableMapOf(
        "Todo" to mutableListOf("Pruning document", "Clean up codes"),
        "Doing" to mutableListOf("Array operations"),
        "Done" to mutableListOf("Create a sample page", "Launch demo site"),
    )
    private val _list: MutableStateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
    val list: StateFlow<Map<String, List<String>>> = _list.asStateFlow()

    fun initialSetUp(context: Context) {
        viewModelScope.launch {
            client = Client(context, "10.0.2.2:8080", true)
            if (client.activateAsync().await()) {
                client.attachAsync(document).await()
                client.syncAsync().await()
            }
            document.updateAsync {
                it.setNewObject(DOCUMENT_LIST_KEY)
            }.await()
        }
    }

    fun addCard(title: String) {
        viewModelScope.launch {
            document.updateAsync {
                it.get<JsonObject>(DOCUMENT_LIST_KEY).setNewArray(title)
            }.await()
        }
    }

    fun addItem(title: String, item: String) {
        viewModelScope.launch {
            document.updateAsync {
                it.get<JsonObject>(DOCUMENT_LIST_KEY).get<JsonArray>(title).put(item)
            }.await()
        }
    }

    private fun addItems(title: String, items: List<String>) {
        viewModelScope.launch {
            document.updateAsync {
                items.forEach { item ->
                    it.get<JsonObject>(DOCUMENT_LIST_KEY).get<JsonArray>(title).put(item)
                }
            }.await()
        }
    }

    fun deleteCard(title: String) {
        viewModelScope.launch {
            document.updateAsync {
                it.get<JsonObject>(DOCUMENT_LIST_KEY).remove(title)
            }.await()
        }
    }

    fun updateDocument(jsonObject: JsonObject) {
        val list = mutableMapOf<String, List<String>>()
        jsonObject.keys.forEach {
            list[it] = jsonObject.get<JsonArray>(it).map { (it as JsonPrimitive).value as String }
        }
        viewModelScope.launch {
            _list.emit(list)
        }
    }
}
