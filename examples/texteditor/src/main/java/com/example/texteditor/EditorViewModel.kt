package com.example.texteditor

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dev.yorkie.core.Client
import dev.yorkie.core.Client.SyncMode.Realtime
import dev.yorkie.core.Client.SyncMode.RealtimePushOnly
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.createSingleThreadDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONArray
import dev.yorkie.document.RgaTreeSplitPosStruct as TextPosStructure

class EditorViewModel : ViewModel(), YorkieEditText.TextEventHandler {
    private val unaryClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val client = Client(
        options = Client.Options(
            apiKey = BuildConfig.YORKIE_API_KEY,
        ),
        host = BuildConfig.YORKIE_SERVER_URL,
        unaryClient = unaryClient,
        streamClient = unaryClient,
        dispatcher = createSingleThreadDispatcher("YorkieClient"),
    )

    private val document = Document(
        Document.Key(
            run {
                val date = Date()
                val formatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                "codemirror6-${formatter.format(date)}"
            },
        ),
    )

    private val _content = MutableSharedFlow<String>()
    val content = _content.asSharedFlow()

    private val _editOpInfos = MutableSharedFlow<OperationInfo.EditOpInfo>()
    val editOpInfos = _editOpInfos.asSharedFlow()

    private val _selections = MutableSharedFlow<Selection>()
    val selections = _selections.asSharedFlow()

    val removedPeers = document.events.filterIsInstance<PresenceChanged.Others.Unwatched>()
        .map { it.changed.actorID }

    private val _selectionColors = mutableMapOf<ActorID, Int>()
    val selectionColors: Map<ActorID, Int>
        get() = _selectionColors

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers = _peers.asStateFlow()

    private var myClientId = ""

    private val gson = Gson()

    init {
        viewModelScope.launch {
            if (client.activateAsync().await().isSuccess &&
                client.attachAsync(document).await().isSuccess
            ) {
                if (document.getRoot().getAsOrNull<JsonText>(CONTENT) == null) {
                    document.updateAsync { root, _ ->
                        root.setNewText(CONTENT)
                    }.await()
                }

                client.syncAsync().await()
            }
        }

        viewModelScope.launch {
            document.events.collect { event ->
                when (event) {
                    is Document.Event.Snapshot -> {
                        syncText()
                    }

                    is Document.Event.RemoteChange -> {
                        emitEditOpInfos(event.changeInfo)
                    }

                    is PresenceChanged.MyPresence.Initialized -> {
                        _peers.update {
                            event.initialized.keys.map { it.value }
                        }
                    }

                    is PresenceChanged.Others.PresenceChanged -> {
                        event.changed.emitSelection()
                        _peers.update {
                            val actorId = event.changed.actorID.value
                            if (actorId !in it) {
                                it + actorId
                            } else {
                                it
                            }
                        }
                    }

                    is PresenceChanged.MyPresence.PresenceChanged -> {
                        _peers.update {
                            val actorId = event.changed.actorID.value
                            val peers = ArrayList<String>().apply {
                                addAll(it)
                            }
                            if (myClientId != actorId) {
                                peers.remove(myClientId)
                                peers.add(actorId)
                                myClientId = actorId
                            }
                            peers
                        }
                    }

                    is PresenceChanged.Others.Unwatched -> {
                        _peers.update {
                            it.filter { it != event.changed.actorID.value }
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    fun syncText() {
        viewModelScope.launch {
            val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)
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
            val (from, to) = document.getRoot().getAs<JsonText>(CONTENT)
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
                val range = root.getAs<JsonText>(CONTENT).edit(from, to, content.toString())
                range?.let {
                    handleSelectEvent(range.first, range.second)
                }
            }.await()
        }
    }

    override fun handleSelectEvent(from: Int, to: Int) {
        if (client.isActive && document.status == Document.DocStatus.Attached) {
            viewModelScope.launch {
                document.updateAsync { root, presence ->
                    val range = root.getAs<JsonText>(CONTENT)
                        .indexRangeToPosRange(from to to)?.toList()
                    presence.put(mapOf("selection" to gson.toJson(range)))
                }.await()
            }
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
        private const val CONTENT = "content"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}
