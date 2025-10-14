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
    val peers: List<String> = emptyList(),
    val selectionPeers: Map<ActorID, Selection?> = emptyMap(),
    val content: String = "",
    val textSelection: Pair<Int, Int> = Pair(0, 0),
    val styleOperations: List<OperationInfo.StyleOpInfo> = emptyList(),
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
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
                        ),
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
                            syncTextSnapShot()
                        }

                        is Document.Event.LocalChange -> {
                            // Only sync text, NOT selection (to avoid cursor jumping)
                            val selectionPeers = document.presences.value.mapValues {
                                it.value.mapToSelection(it.key)
                            }
                            _uiState.update {
                                it.copy(
                                    selectionPeers = selectionPeers,
                                )
                            }
                        }

                        is Document.Event.RemoteChange -> {
                            // Sync both text and selection for remote changes
                            syncTextRemoteChanged(event.changeInfo)
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
     * Syncs the text content snapshot from the Yorkie document to the UI state
     */
    private suspend fun syncTextSnapShot() {
        val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)
        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        content?.values?.forEachIndexed { index, textWithAttributes ->
            newStyleOperations.add(
                OperationInfo.StyleOpInfo(
                    from = index,
                    to = index + 1,
                    attributes = textWithAttributes.attributes,
                ),
            )
        }
        _uiState.update {
            it.copy(
                content = content?.toString().orEmpty(),
                styleOperations = newStyleOperations,
            )
        }
    }

    private suspend fun syncTextRemoteChanged(changeInfo: Document.Event.ChangeInfo) {
        val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)
        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        content?.values?.forEachIndexed { index, textWithAttributes ->
            newStyleOperations.add(
                OperationInfo.StyleOpInfo(
                    from = index,
                    to = index + 1,
                    attributes = textWithAttributes.attributes,
                ),
            )
        }

        // Adjust local text selection based on remote edits
        val editOperations = changeInfo.operations
            .filterIsInstance<OperationInfo.EditOpInfo>()
        var (currentFrom, currentTo) = _uiState.value.textSelection
        for (editOpInfo in editOperations) {
            val newSelection = calculateNewSelection(
                currentFrom = currentFrom,
                currentTo = currentTo,
                editFrom = editOpInfo.from,
                editTo = editOpInfo.to,
                editValue = editOpInfo.value.text,
            )
            currentFrom = newSelection.first
            currentTo = newSelection.second
        }

        _uiState.update { state ->
            state.copy(
                content = content?.toString().orEmpty(),
                styleOperations = newStyleOperations,
                selectionPeers = document.presences.value.mapValues {
                    it.value.mapToSelection(it.key)
                },
                textSelection = Pair(currentFrom, currentTo),
            )
        }
    }

    private suspend fun Map<String, String>.mapToSelection(actorID: ActorID): Selection? {
        if (actorID == myClientId) return null

        val jsonArray = gson.fromJson<List<RgaTreeSplitPosStruct>>(
            this["selection"],
            object : TypeToken<List<RgaTreeSplitPosStruct>>() {}.type,
        )
        return try {
            if (jsonArray != null && jsonArray.size >= 2) {
                val fromPos = jsonArray[0]
                val toPos = jsonArray[1]

                val content = document.getRoot().getAsOrNull<JsonText>(CONTENT) ?: return null

                val (from, to) = content.posRangeToIndexRange(fromPos to toPos)

                // Validate indices against actual content length
                val contentLength = content.toString().length
                val validFrom = from.coerceIn(0, contentLength)
                val validTo = to.coerceIn(0, contentLength)

                Selection(
                    name = gson.fromJson(this["username"], String::class.java),
                    from = validFrom,
                    to = validTo,
                    color = gson.fromJson(this["color"], String::class.java),
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun changeContent(content: String) {
        _uiState.update {
            it.copy(
                content = content,
            )
        }
    }

    /**
     * Handles local edit events from the UI and syncs to Yorkie document
     */
    fun handleEditEvent(
        from: Int,
        to: Int,
        content: String,
    ) {
        val styles = ArrayList<EditStyle>()
        if (_uiState.value.isBold) {
            styles.add(EditStyle.BOLD)
        }
        if (_uiState.value.isItalic) {
            styles.add(EditStyle.ITALIC)
        }
        if (_uiState.value.isUnderline) {
            styles.add(EditStyle.UNDERLINE)
        }
        if (_uiState.value.isStrikethrough) {
            styles.add(EditStyle.STRIKETHROUGH)
        }

        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        newStyleOperations.add(
            OperationInfo.StyleOpInfo(
                from = from,
                to = to,
                attributes = styles.associate {
                    it.key to "true"
                },
            ),
        )
        _uiState.update {
            it.copy(
                styleOperations = it.styleOperations + newStyleOperations,
            )
        }

        viewModelScope.launch {
            document.updateAsync { root, _ ->
                root.getAs<JsonText>(CONTENT).edit(
                    fromIndex = from,
                    toIndex = to,
                    content = content,
                    attributes = styles.associate {
                        it.key to "true"
                    },
                )
            }.await()
        }
    }

    fun handleSelectEvent(from: Int, to: Int) {
        viewModelScope.launch {
            // Update local state
            _uiState.update {
                it.copy(textSelection = from to to)
            }

            // Update Yorkie presence
            document.updateAsync { root, presence ->
                val range = root.getAs<JsonText>(CONTENT)
                    .indexRangeToPosRange(from to to)?.toList().orEmpty()
                presence.put(mapOf("selection" to gson.toJson(range)))
            }.await()
        }
    }

    private fun toggleStyle(
        style: EditStyle,
        currentValue: Boolean,
        updateState: (EditorUiState) -> EditorUiState,
    ) {
        _uiState.update(updateState)

        val textSelection = _uiState.value.textSelection
        if (textSelection.first < textSelection.second) {
            viewModelScope.launch {
                document.updateAsync { root, _ ->
                    root.getAs<JsonText>(CONTENT).style(
                        fromIndex = textSelection.first,
                        toIndex = textSelection.second,
                        attributes = mapOf(
                            style.key to if (!currentValue) "true" else "null",
                        ),
                    )
                }.await()
                syncTextSnapShot()
            }
        }
    }

    fun toggleBold() {
        toggleStyle(
            EditStyle.BOLD,
            _uiState.value.isBold,
        ) { it.copy(isBold = !it.isBold) }
    }

    fun toggleItalic() {
        toggleStyle(
            EditStyle.ITALIC,
            _uiState.value.isItalic,
        ) { it.copy(isItalic = !it.isItalic) }
    }

    fun toggleUnderline() {
        toggleStyle(
            EditStyle.UNDERLINE,
            _uiState.value.isUnderline,
        ) { it.copy(isUnderline = !it.isUnderline) }
    }

    fun toggleStrikethrough() {
        toggleStyle(
            EditStyle.STRIKETHROUGH,
            _uiState.value.isStrikethrough,
        ) { it.copy(isStrikethrough = !it.isStrikethrough) }
    }

    fun clearFormatting() {
        _uiState.update {
            it.copy(
                isBold = false,
                isItalic = false,
                isUnderline = false,
                isStrikethrough = false,
            )
        }
    }

    /**
     * Calculates the new selection position after a remote edit
     */
    private fun calculateNewSelection(
        currentFrom: Int,
        currentTo: Int,
        editFrom: Int,
        editTo: Int,
        editValue: String,
    ): Pair<Int, Int> {
        val deletedLength = editTo - editFrom
        val insertedLength = editValue.length
        val delta = insertedLength - deletedLength

        return when {
            // Selection is before the edit - no change needed
            currentTo <= editFrom -> {
                currentFrom to currentTo
            }

            // Selection starts after the edit - shift by delta
            currentFrom >= editTo -> {
                (currentFrom + delta) to (currentTo + delta)
            }

            // Selection overlaps with edit - collapse to end of edit
            else -> {
                val newPos = editFrom + insertedLength
                newPos to newPos
            }
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

    internal enum class EditStyle(
        val key: String,
    ) {
        BOLD("bold"),
        ITALIC("italic"),
        UNDERLINE("underline"),
        STRIKETHROUGH("strike"),
    }
}

private class ActorIDAdapter : JsonDeserializer<ActorID>, JsonSerializer<ActorID> {
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
