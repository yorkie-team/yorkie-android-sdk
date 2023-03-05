package com.example.texteditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.TextChange
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

    private val remoteChangeEventHandler: ((List<TextChange>) -> Unit) = { changes ->
        val clientID = client.requireClientId()
        changes.filterNot { it.actor == clientID }.forEach {
            viewModelScope.launch {
                _textChanges.emit(it)
            }
        }
    }

    var configurationChanged = false

    init {
        viewModelScope.launch {
            if (client.activateAsync().await()) {
                client.attachAsync(document).await()
                document.getRoot().getAsOrNull<JsonText>(TEXT_KEY)
                    ?.onChanges(remoteChangeEventHandler)
                    ?: run {
                        document.updateAsync {
                            it.setNewText(TEXT_KEY).onChanges(remoteChangeEventHandler)
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
        if (configurationChanged) {
            configurationChanged = false
            return
        }

        viewModelScope.launch {
            document.updateAsync {
                val jsonText = it.getAs<JsonText>(TEXT_KEY)
                jsonText.edit(from, to, content.toString())
            }.await()
        }
    }

    override fun handleSelectEvent(from: Int, to: Int) {
        viewModelScope.launch {
            document.updateAsync {
                val jsonText = it.getAs<JsonText>(TEXT_KEY)
                jsonText.select(from, to)
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
