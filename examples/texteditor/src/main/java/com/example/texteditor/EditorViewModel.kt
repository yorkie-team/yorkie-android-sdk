package com.example.texteditor

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yorkie.core.Client
import dev.yorkie.core.Client.Event
import dev.yorkie.core.Client.PeersChangedResult.Unwatched
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.random.Random

class EditorViewModel(private val client: Client) : ViewModel(), YorkieEditText.TextEventHandler {
    private val document = Document(Document.Key(DOCUMENT_KEY))

    private val _content = MutableSharedFlow<String>()
    val content = _content.asSharedFlow()

    private val _textOpInfos = MutableSharedFlow<Pair<ActorID, OperationInfo.TextOpInfo>>()
    val textOpInfos = _textOpInfos.asSharedFlow()

    val removedPeers = client.events.filterIsInstance<Event.PeersChanged>()
        .map { it.result }
        .filterIsInstance<Unwatched>()
        .mapNotNull { it.changedPeers[document.key]?.keys }
        .filterNot { it.isEmpty() }

    private val _peerSelectionInfos = mutableMapOf<ActorID, PeerSelectionInfo>()
    val peerSelectionInfos: Map<ActorID, PeerSelectionInfo>
        get() = _peerSelectionInfos

    init {
        viewModelScope.launch {
            if (client.activateAsync().await() && client.attachAsync(document).await()) {
                if (document.getRoot().getAsOrNull<JsonText>(TEXT_KEY) == null) {
                    document.updateAsync {
                        it.setNewText(TEXT_KEY)
                    }.await()
                }
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            document.events.collect { event ->
                if (event is Document.Event.Snapshot) {
                    syncText()
                } else if (event is Document.Event.RemoteChange) {
                    emitTextOpInfos(event.changeInfo)
                }
            }
        }
    }

    private suspend fun emitTextOpInfos(changeInfo: Document.Event.ChangeInfo) {
        changeInfo.operations.filterIsInstance<OperationInfo.TextOpInfo>()
            .forEach { opInfo ->
                _textOpInfos.emit(changeInfo.actorID to opInfo)
            }
    }

    fun syncText() {
        viewModelScope.launch {
            val content = document.getRoot().getAsOrNull<JsonText>(TEXT_KEY)
            _content.emit(content?.toString().orEmpty())
        }
    }

    override fun handleEditEvent(from: Int, to: Int, content: CharSequence) {
        viewModelScope.launch {
            document.updateAsync {
                it.getAs<JsonText>(TEXT_KEY).edit(from, to, content.toString())
            }.await()
        }
    }

    override fun handleSelectEvent(from: Int, to: Int) {
        viewModelScope.launch {
            document.updateAsync {
                it.getAs<JsonText>(TEXT_KEY).select(from, to)
            }.await()
        }
    }

    @ColorInt
    fun getPeerSelectionColor(actorID: ActorID): Int {
        return _peerSelectionInfos[actorID]?.color ?: run {
            val newColor =
                Color.argb(51, Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
            _peerSelectionInfos[actorID] = PeerSelectionInfo(newColor)
            newColor
        }
    }

    fun updatePeerPrevSelection(actorID: ActorID, prevSelection: Pair<Int, Int>?) {
        val peerSelectionInfo = _peerSelectionInfos[actorID] ?: return
        _peerSelectionInfos[actorID] = peerSelectionInfo.copy(prevSelection = prevSelection)
    }

    fun removeDetachedPeerSelectionInfo(actorID: ActorID) {
        _peerSelectionInfos.remove(actorID)
    }

    override fun handleHangulCompositionStart() {
        client.pause(document)
    }

    override fun handleHangulCompositionEnd() {
        client.resume(document)
    }

    override fun onCleared() {
        TerminationScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
    }

    data class PeerSelectionInfo(
        @ColorInt val color: Int,
        val prevSelection: Pair<Int, Int>? = null,
    )

    companion object {
        private const val DOCUMENT_KEY = "document-key"
        private const val TEXT_KEY = "text-key"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}
