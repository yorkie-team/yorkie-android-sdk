package dev.yorkie.examples

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Key
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KanbanBoardViewModel : ViewModel() {
    private val document = Document(Key(DOCUMENT_KEY))

    private val _list: MutableStateFlow<List<KanbanColumn>> = MutableStateFlow(emptyList())
    val list: StateFlow<List<KanbanColumn>> = _list.asStateFlow()

    fun init(context: Context) {
        val client = Client(context, "10.0.2.2:8080", true)
        viewModelScope.launch {
            if (client.activateAsync().await()) {
                client.attachAsync(document).await()
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            client.collect {
                if (it is Client.Event.DocumentSynced) {
                    try {
                        updateDocument(it.value.document.getRoot()[DOCUMENT_LIST_KEY])
                    } catch (e: Exception) {
                        document.updateAsync { jsonObject ->
                            jsonObject.setNewArray(DOCUMENT_LIST_KEY)
                        }.await()
                    }
                }
            }
        }
    }

    fun addCardColumn(column: KanbanColumn) {
        viewModelScope.launch {
            document.updateAsync {
                it.get<JsonArray>(DOCUMENT_LIST_KEY).putNewObject().apply {
                    set("title", column.title)
                    setNewArray("cards")
                }.also { newObject ->
                    column.id = newObject.id
                }
            }.await()
        }
    }

    fun addCardToColumn(cardColumn: KanbanColumn, card: Card) {
        viewModelScope.launch {
            document.updateAsync {
                val column = it.get<JsonArray>(DOCUMENT_LIST_KEY)[cardColumn.id] as JsonObject
                column.get<JsonArray>("cards").putNewObject().apply {
                    set("title", card.title)
                }.also { newObject ->
                    card.id = newObject.id
                }
            }.await()
        }
    }

    fun deleteCardColumn(cardColumn: KanbanColumn) {
        viewModelScope.launch {
            document.updateAsync {
                it.get<JsonArray>(DOCUMENT_LIST_KEY).remove(cardColumn.id)
            }.await()
        }
    }

    private fun updateDocument(lists: JsonArray) {
        viewModelScope.launch {
            lists.map {
                val column = it as JsonObject
                val title = column.get<JsonPrimitive>("title").value as String
                val cards = column.get<JsonArray>("cards").map { card ->
                    val cardTitle = (card as JsonObject).get<JsonPrimitive>("title").value as String
                    Card(cardTitle)
                }
                KanbanColumn(title = title, cards = cards, id = it.id)
            }.also {
                _list.emit(it)
            }
        }
    }

    companion object {
        private const val DOCUMENT_KEY = "test key3"
        private const val DOCUMENT_LIST_KEY = "lists"
    }
}
