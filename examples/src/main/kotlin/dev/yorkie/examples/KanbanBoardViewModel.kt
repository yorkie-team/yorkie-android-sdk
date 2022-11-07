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
                }
            }.await()
        }
    }

    fun addCardToColumn(cardColumn: KanbanColumn, card: Card) {
        viewModelScope.launch {
            document.updateAsync {
                TODO("not yet implemented")
            }.await()
        }
    }

    fun deleteCardColumn(cardColumn: KanbanColumn) {
        viewModelScope.launch {
            document.updateAsync {
                TODO("not yet implemented")
            }.await()
        }
    }

    private fun updateDocument(jsonArray: JsonArray) {
        val list = jsonArray.map {
            val jsonObject = it as JsonObject
            val title = jsonObject.get<JsonPrimitive>("title").value as String
            val cards = jsonObject.get<JsonArray>("cards").map { Card((it as JsonPrimitive).value as String) }
            KanbanColumn(title = title, cards = cards)
        }
        viewModelScope.launch {
            _list.emit(list)
        }
    }

    companion object {
        private const val DOCUMENT_KEY = "kanban-board3243"
        private const val DOCUMENT_LIST_KEY = "list"
    }
}
