package com.example.texteditor

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dev.yorkie.core.Client
import dev.yorkie.core.PresenceInfo
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChange
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.random.Random
import dev.yorkie.document.RgaTreeSplitPosStruct as TextPosStructure

class EditorViewModel(private val client: Client) : ViewModel(), YorkieEditText.TextEventHandler {
    private val document = Document(Document.Key(DOCUMENT_KEY))

    private val _content = MutableSharedFlow<String>()
    val content = _content.asSharedFlow()

    private val _textOpInfos = MutableSharedFlow<Pair<ActorID, OperationInfo.TextOpInfo>>()
    val textOpInfos = _textOpInfos.asSharedFlow()

    val removedPeers = document.events.filterIsInstance<PresenceChange.Others.Unwatched>()
        .map { it.unwatched }

    private val _peerSelectionInfos = mutableMapOf<ActorID, PeerSelectionInfo>()
    val peerSelectionInfos: Map<ActorID, PeerSelectionInfo>
        get() = _peerSelectionInfos

    private val gson = Gson()

    init {
        viewModelScope.launch {
            if (document.getRoot().getAsOrNull<JsonText>(TEXT_KEY) == null) {
                document.updateAsync { root, _ ->
                    root.setNewText(TEXT_KEY)
                }.await()
            }

            if (client.activateAsync().await() && client.attachAsync(document).await()) {
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            document.events.collect { event ->
                when (event) {
                    is Document.Event.Snapshot -> syncText()

                    is Document.Event.RemoteChange -> emitEditOpInfos(event.changeInfo)

                    is PresenceChange.Others.PresenceChanged -> event.changed.emitSelectOpInfo()

                    else -> {}
                }
            }
        }
    }

    private suspend fun emitEditOpInfos(changeInfo: Document.Event.ChangeInfo) {
        changeInfo.operations.filterIsInstance<OperationInfo.EditOpInfo>()
            .forEach { opInfo ->
                _textOpInfos.emit(changeInfo.actorID to opInfo)
            }
    }

    private suspend fun PresenceInfo.emitSelectOpInfo() {
        val jsonArray = JSONArray(presence["selection"] ?: return)
        val fromPos =
            gson.fromJson(jsonArray.getString(0), TextPosStructure::class.java) ?: return
        val toPos =
            gson.fromJson(jsonArray.getString(1), TextPosStructure::class.java) ?: return
        val (from, to) = document.getRoot().getAs<JsonText>(TEXT_KEY)
            .posRangeToIndexRange(fromPos to toPos)
        _textOpInfos.emit(actorID to OperationInfo.SelectOpInfo(from, to))
    }

    fun syncText() {
        viewModelScope.launch {
            val content = document.getRoot().getAsOrNull<JsonText>(TEXT_KEY)
            _content.emit(content?.toString().orEmpty())
        }
    }

    override fun handleEditEvent(from: Int, to: Int, content: CharSequence) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val range = root.getAs<JsonText>(TEXT_KEY).edit(from, to, content.toString())
                range?.let {
                    handleSelectEvent(range.first, range.second)
                }
            }.await()
        }
    }

    override fun handleSelectEvent(from: Int, to: Int) {
        viewModelScope.launch {
            document.updateAsync { root, presence ->
                val range = root.getAs<JsonText>(TEXT_KEY)
                    .indexRangeToPosRange(from to to)?.toList()
                presence.set(mapOf("selection" to gson.toJson(range)))
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
