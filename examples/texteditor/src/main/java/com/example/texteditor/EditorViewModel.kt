package com.example.texteditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.TextChange
import dev.yorkie.document.crdt.TextChangeType
import dev.yorkie.document.json.JsonText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

class EditorViewModel(private val client: Client) : ViewModel(), YorkieEditText.TextEventHandler {
    private val document = Document(Document.Key(DOCUMENT_KEY))

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val changeEventHandler: ((List<TextChange>) -> Unit) = { changes ->
        val clientID = client.requireClientId()
        changes.filter { it.type == TextChangeType.Content && it.actor != clientID }
            .forEach {
                _content.value =
                    _content.value.replaceRange(max(0, it.from), max(0, it.to), it.content ?: "")
            }
    }

    init {
        viewModelScope.launch {
            if (client.activateAsync().await()) {
                client.attachAsync(document).await()
                document.getRoot().getAsOrNull<JsonText>(TEXT_KEY)?.onChanges(changeEventHandler)
                    ?: run {
                        document.updateAsync {
                            it.setNewText(TEXT_KEY).onChanges(changeEventHandler)
                        }.await()
                    }
                syncText()
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            document.collect {
                if (it is Document.Event.Snapshot) {
                    syncText()
                }
            }
        }
    }

    private fun syncText() {
        viewModelScope.launch {
            val content = document.getRoot().getAsOrNull<JsonText>(TEXT_KEY)
            _content.value = content.toString()
        }
    }

    override fun handleEditEvent(from: Int, to: Int, content: String) {
        viewModelScope.launch {
            document.updateAsync {
                val jsonText = it.getAs<JsonText>(TEXT_KEY)
                jsonText.edit(from, max(to, jsonText.toString().length), content)
            }.await()
        }
    }

    override fun onCleared() {
        coroutineScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
    }

    companion object {
        private const val DOCUMENT_KEY = "text-editor"
        private const val TEXT_KEY = "content"
    }
}
