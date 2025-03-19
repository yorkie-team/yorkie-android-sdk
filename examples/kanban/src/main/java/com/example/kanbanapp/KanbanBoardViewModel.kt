package com.example.kanbanapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Key
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KanbanBoardViewModel(private val client: Client) : ViewModel() {
    private val document = Document(Key(DOCUMENT_KEY))

    private val _list = MutableStateFlow<ImmutableList<KanbanColumn>>(persistentListOf())
    val list: StateFlow<ImmutableList<KanbanColumn>> = _list.asStateFlow()

    private val _greetingBroadcast: MutableStateFlow<String?> = MutableStateFlow(null)
    val greetingBroadcast: StateFlow<String?> = _greetingBroadcast.asStateFlow()

    init {
        viewModelScope.launch {
            if (client.activateAsync().await().isSuccess &&
                client.attachAsync(document).await().isSuccess
            ) {
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            document.events.collect {
                if (it is Document.Event.SyncStatusChanged.Synced) {
                    document.getRoot().getAsOrNull<JsonArray>(DOCUMENT_LIST_KEY)
                        ?.let(::updateDocument)
                        ?: run {
                            document.updateAsync { root, _ ->
                                root.setNewArray(DOCUMENT_LIST_KEY)
                            }.await()
                        }
                }
                if (it is Document.Event.Broadcast) {
                    when (it.topic) {
                        BROADCAST_GREETING -> _greetingBroadcast.value = it.payload
                    }
                }
            }
        }
    }

    fun addCardColumn(title: String) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                root.getAs<JsonArray>(DOCUMENT_LIST_KEY).putNewObject().apply {
                    set("title", title)
                    setNewArray("cards")
                }
            }.await()
        }
    }

    fun addCardToColumn(cardColumn: KanbanColumn, title: String) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val column = root.getAs<JsonArray>(DOCUMENT_LIST_KEY).getAs<JsonObject>(
                    cardColumn.id,
                )
                column?.getAs<JsonArray>("cards")?.putNewObject()?.apply {
                    set("title", title)
                }
            }.await()
        }
    }

    fun deleteCardColumn(cardColumn: KanbanColumn) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                root.getAs<JsonArray>(DOCUMENT_LIST_KEY).remove(cardColumn.id)
            }.await()
        }
    }

    private fun updateDocument(lists: JsonArray) {
        viewModelScope.launch {
            _list.value = lists.filterIsInstance<JsonObject>()
                .map { column ->
                    val title = column.getAs<JsonPrimitive>("title").getValueAs<String>()
                    val cards = column.getAs<JsonArray>("cards")
                        .filterIsInstance<JsonObject>()
                        .map { card ->
                            Card(card.getAs<JsonPrimitive>("title").getValueAs())
                        }
                        .toImmutableList()
                    KanbanColumn(title = title, cards = cards, id = column.id)
                }
                .toImmutableList()
        }
    }

    fun broadcastMessage() {
        val payload = "hello".toList().shuffled().joinToString("")
        viewModelScope.launch {
            client.broadcast(document, BROADCAST_GREETING, payload)
                .await().isSuccess
        }
    }

    companion object {
        private const val DOCUMENT_KEY = "vuejs-kanban"
        private const val DOCUMENT_LIST_KEY = "lists"

        private const val BROADCAST_GREETING = "GREETING"
    }
}
