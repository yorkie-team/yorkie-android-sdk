package com.example.texteditor

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dev.yorkie.core.Client
import dev.yorkie.core.Client.SyncMode.Realtime
import dev.yorkie.core.Client.SyncMode.RealtimePushOnly
import dev.yorkie.core.PresenceInfo
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChange
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import dev.yorkie.document.RgaTreeSplitPosStruct as TextPosStructure

class EditorViewModel(private val client: Client) : ViewModel(), YorkieEditText.TextEventHandler {
    private val document = Document(Document.Key(DOCUMENT_KEY))

    private val _content = MutableSharedFlow<String>()
    val content = _content.asSharedFlow()

    private val _editOpInfos = MutableSharedFlow<OperationInfo.EditOpInfo>()
    val editOpInfos = _editOpInfos.asSharedFlow()

    private val _selections = MutableSharedFlow<Selection>()
    val selections = _selections.asSharedFlow()

    val removedPeers = document.events.filterIsInstance<PresenceChange.Others.Unwatched>()
        .map { it.changed.actorID }

    private val _selectionColors = mutableMapOf<ActorID, Int>()
    val selectionColors: Map<ActorID, Int>
        get() = _selectionColors

    private val gson = Gson()

    init {
        viewModelScope.launch {
            if (document.getRoot().getAsOrNull<JsonText>(TEXT_KEY) == null) {
                document.updateAsync { root, _ ->
                    root.setNewText(TEXT_KEY)
                }.await()
            }

            if (client.activateAsync().await().isSuccess &&
                client.attachAsync(document).await().isSuccess
            ) {
                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            document.events.collect { event ->
                when (event) {
                    is Document.Event.Snapshot -> syncText()

                    is Document.Event.RemoteChange -> emitEditOpInfos(event.changeInfo)

                    is PresenceChange.Others.PresenceChanged -> event.changed.emitSelection()

                    else -> {}
                }
            }
        }
    }

    fun syncText() {
        viewModelScope.launch {
            val content = document.getRoot().getAsOrNull<JsonText>(TEXT_KEY)
            _content.emit(content?.toString().orEmpty())
        }
    }

    private suspend fun emitEditOpInfos(changeInfo: Document.Event.ChangeInfo) {
        changeInfo.operations.filterIsInstance<OperationInfo.EditOpInfo>()
            .forEach { _editOpInfos.emit(it) }
    }

    private suspend fun PresenceInfo.emitSelection() {
        runCatching {
            delay(500)
            val jsonArray = JSONArray(presence["selection"] ?: return)
            val fromPos =
                gson.fromJson(jsonArray.getString(0), TextPosStructure::class.java) ?: return
            val toPos =
                gson.fromJson(jsonArray.getString(1), TextPosStructure::class.java) ?: return
            val (from, to) = document.getRoot().getAs<JsonText>(TEXT_KEY)
                .posRangeToIndexRange(fromPos to toPos)
            _selections.emit(Selection(actorID, from, to))
        }
    }

    override fun handleEditEvent(
        from: Int,
        to: Int,
        content: CharSequence,
    ) {
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
                presence.put(mapOf("selection" to gson.toJson(range)))
            }.await()
        }
    }

    @ColorInt
    fun getPeerSelectionColor(actorID: ActorID): Int {
        return _selectionColors.getOrPut(actorID) {
            Color.argb(51, Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
        }
    }

    fun removeUnwatchedPeerSelectionInfo(actorID: ActorID) {
        _selectionColors.remove(actorID)
    }

    override fun handleHangulCompositionStart() {
        client.changeSyncMode(document, RealtimePushOnly)
    }

    override fun handleHangulCompositionEnd() {
        client.changeSyncMode(document, Realtime)
    }

    override fun onCleared() {
        TerminationScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
    }

    data class Selection(val clientID: ActorID, val from: Int, val to: Int)

    companion object {
        private const val DOCUMENT_KEY = "document-key"
        private const val TEXT_KEY = "text-key"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}
