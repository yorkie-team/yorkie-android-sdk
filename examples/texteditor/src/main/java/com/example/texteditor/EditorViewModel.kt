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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class EditorViewModel(private val client: Client) : ViewModel(), YorkieEditText.TextEventHandler {
    private val document = Document(Document.Key(DOCUMENT_KEY))

    private val _content = MutableSharedFlow<String>()
    val content = _content.asSharedFlow()

    private val _textChanges = MutableSharedFlow<TextChange>()
    val textChanges = _textChanges.asSharedFlow()

    private val changeEventHandler: ((List<TextChange>) -> Unit) = { changes ->
        val clientID = client.requireClientId()
        changes.filter { it.type == TextChangeType.Content && it.actor != clientID }
            .forEach {
                viewModelScope.launch {
                    _textChanges.emit(it)
                }
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
                client.syncAsync().await()
                syncText()
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
            _content.emit(content?.toString().orEmpty())
        }
    }

    override fun handleEditEvent(from: Int, to: Int, content: CharSequence) {
        viewModelScope.launch {
            document.updateAsync {
                val jsonText = it.getAs<JsonText>(TEXT_KEY)
                jsonText.edit(from, to, content.toString())
            }.await()
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
        private const val DOCUMENT_KEY = "document-key"
        private const val TEXT_KEY = "text-key"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}
