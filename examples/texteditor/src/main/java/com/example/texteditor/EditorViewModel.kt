package com.example.texteditor

import android.content.Context
import androidx.databinding.ObservableField
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlin.math.max

class EditorViewModel : ViewModel(), YorkieEditText.TextEventHandler {
    private lateinit var client: Client
    private val document = Document(Document.Key(DOCUMENT_KEY))
    val content = ObservableField("")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + NonCancellable)

    fun init(context: Context) {
        client = Client(context, "api.yorkie.dev", 443)
        viewModelScope.launch {
            if (client.activateAsync().await()) {
                client.attachAsync(document).await()
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            client.collect { event ->
                if (event is Client.Event.DocumentSynced) {
                    val text = document.getRoot().getAsOrNull<JsonText>(TEXT_KEY) ?: run {
                        document.updateAsync {
                            it.setNewText(TEXT_KEY)
                        }.await()
                        return@collect
                    }
                    content.set(text.toString())
                }
            }
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

    fun onDestroy() {
        coroutineScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }

    companion object {
        private const val DOCUMENT_KEY = "text-editor"
        private const val TEXT_KEY = "content"
    }
}
