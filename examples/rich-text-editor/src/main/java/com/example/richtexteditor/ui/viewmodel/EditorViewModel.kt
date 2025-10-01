package com.example.richtexteditor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.richtexteditor.BuildConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import dev.yorkie.core.Client
import dev.yorkie.core.Client.SyncMode.Realtime
import dev.yorkie.core.Client.SyncMode.RealtimePushOnly
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.document.RgaTreeSplitPosStruct
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.createSingleThreadDispatcher
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol

data class EditorUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val content: String = "",
    val peers: List<String> = emptyList(),
    val selectionPeers: Map<ActorID, Selection?> = emptyMap(),
    val editOperations: List<OperationInfo.EditOpInfo> = emptyList(),
)

data class Selection(
    val name: String,
    val from: Int,
    val to: Int,
    val color: String,
)

class EditorViewModel : ViewModel() {
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
                "vanilla-quill-${formatter.format(date)}"
            },
        ),
    )

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    private var myClientId = ActorID("")

    private val gson = GsonBuilder()
        .registerTypeAdapter(ActorID::class.java, ActorIDAdapter())
        .create()

    init {
        viewModelScope.launch {
            try {
                if (client.activateAsync().await().isSuccess &&
                    client.attachAsync(
                        document,
                        initialPresence = mapOf(
                            "username" to "\"Fiction\"",
                            "color" to "\"${randomColorString()}\"",
                        )
                    ).await().isSuccess
                ) {
                    if (document.getRoot().getAsOrNull<JsonText>(CONTENT) == null) {
                        document.updateAsync { root, _ ->
                            root.setNewText(CONTENT)
                        }.await()
                    }

                    client.syncAsync().await()
                    _uiState.update { it.copy(isLoading = false) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to connect to Yorkie server",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error occurred",
                    )
                }
            }
        }

        viewModelScope.launch {
            launch {
                document.events.collect { event ->
                    when (event) {
                        is Document.Event.Snapshot -> {
                            syncText()
                        }

                        is Document.Event.RemoteChange -> {
                            handleRemoteEditOperations(event.changeInfo)
                            val selectionPeers = document.presences.value.mapValues {
                                it.value.mapToSelection(it.key)
                            }
                            _uiState.update {
                                it.copy(
                                    selectionPeers = selectionPeers,
                                )
                            }
                        }

                        is PresenceChanged.Others.PresenceChanged -> {
                            val actorID = event.changed.actorID
                            val peerSelection = event.changed.presence.mapToSelection(actorID)
                            val selectionPeers = _uiState.value.selectionPeers.toMutableMap()
                            selectionPeers[actorID] = peerSelection
                            _uiState.update {
                                it.copy(
                                    selectionPeers = selectionPeers,
                                )
                            }
                        }

                        is PresenceChanged.MyPresence.PresenceChanged -> {
                            val newActorId = event.changed.actorID
                            if (myClientId != newActorId) {
                                myClientId = newActorId
                            }
                        }

                        is PresenceChanged.Others.Unwatched -> {
                            val unwatchedActorId = event.changed.actorID
                            removeUnwatchedPeerSelectionInfo(unwatchedActorId)
                        }

                        is PresenceChanged.MyPresence.Initialized -> {
                            val selectionPeers = document.presences.value.mapValues {
                                it.value.mapToSelection(it.key)
                            }
                            _uiState.update {
                                it.copy(
                                    selectionPeers = selectionPeers,
                                )
                            }
                        }

                        else -> {}
                    }
                }
            }

            launch {
                document.events.filterIsInstance<PresenceChanged>().collect {
                    val peers = document.presences.value.values.map {
                        gson.fromJson(it["username"], String::class.java)
                    }
                    _uiState.update {
                        it.copy(
                            peers = peers,
                        )
                    }
                }
            }
        }
    }

    /**
     * Syncs the text content from the Yorkie document to the UI state
     */
    private fun syncText() {
        viewModelScope.launch {
            val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)
            _uiState.update { it.copy(content = content?.toString().orEmpty()) }
        }
    }

    /**
     * Handles remote edit operations and adds them to the uiState list
     * Also extracts cursor position from the operations to show remote selections
     */
    private fun handleRemoteEditOperations(changeInfo: Document.Event.ChangeInfo) {
        viewModelScope.launch {
            val newOperations = changeInfo.operations
                .filterIsInstance<OperationInfo.EditOpInfo>()
            _uiState.update { currentState ->
                currentState.copy(
                    editOperations = currentState.editOperations + newOperations,
                )
            }
        }
    }

    private suspend fun Map<String, String>.mapToSelection(actorID: ActorID): Selection? {
        if (actorID == myClientId) return null

        val jsonArray = gson.fromJson<List<RgaTreeSplitPosStruct>>(
            this["selection"],
            object : TypeToken<List<RgaTreeSplitPosStruct>>() {}.type,
        )
        val fromPos = jsonArray.getOrNull(0) ?: return null
        val toPos = jsonArray.getOrNull(1) ?: return null
        val (from, to) = document.getRoot().getAs<JsonText>(CONTENT)
            .posRangeToIndexRange(fromPos to toPos)
        return Selection(
            name = gson.fromJson(this["username"], String::class.java),
            from = from,
            to = to,
            color = gson.fromJson(this["color"], String::class.java),
        )
    }


    /**
     * Handles local edit events from the UI and syncs to Yorkie document
     */
    fun handleEditEvent(from: Int, to: Int, content: String) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                root.getAs<JsonText>(CONTENT).edit(from, to, content)
            }.await()
        }
    }

    fun handleSelectEvent(from: Int, to: Int) {
        viewModelScope.launch {
            document.updateAsync { root, presence ->
                val range = root.getAs<JsonText>(CONTENT)
                    .indexRangeToPosRange(from to to)?.toList()
                presence.put(mapOf("selection" to gson.toJson(range)))
            }.await()
        }
    }

    /**
     * Removes selection color info when a peer disconnects
     */
    private fun removeUnwatchedPeerSelectionInfo(actorID: ActorID) {
        _uiState.update { currentState ->
            val updatedSelections = currentState.selectionPeers.toMutableMap()
            updatedSelections.remove(actorID)
            currentState.copy(selectionPeers = updatedSelections)
        }
    }

    /**
     * Clears the edit operations list after they have been processed
     */
    fun clearEditOperations() {
        _uiState.update { it.copy(editOperations = emptyList()) }
    }

    /**
     * Switches to push-only mode during Hangul composition to prevent conflicts
     */
    fun handleHangulCompositionStart() {
        client.changeSyncMode(document, RealtimePushOnly)
    }

    /**
     * Switches back to real-time mode after Hangul composition ends
     */
    fun handleHangulCompositionEnd() {
        client.changeSyncMode(document, Realtime)
    }

    private fun randomColorString(): String {
        val random = Random.Default
        val color = (0xFFFFFF and random.nextInt()).toString(16).padStart(6, '0')
        return "#$color"
    }

    override fun onCleared() {
        TerminationScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
    }

    companion object {
        private const val CONTENT = "content"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}

class ActorIDAdapter : JsonDeserializer<ActorID>, JsonSerializer<ActorID> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): ActorID {
        return ActorID(json.asString) // map raw string into ActorID
    }

    override fun serialize(
        src: ActorID,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return JsonPrimitive(src.value) // write back as string
    }
}
