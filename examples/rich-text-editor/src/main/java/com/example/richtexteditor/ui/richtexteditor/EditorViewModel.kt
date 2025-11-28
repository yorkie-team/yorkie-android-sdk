package com.example.richtexteditor.ui.richtexteditor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.forEachChange
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import java.lang.reflect.Type
import java.net.URLDecoder
import kotlin.random.Random
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class EditorUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val peers: List<String> = emptyList(),
    val selectionPeers: Map<ActorID, Selection?> = emptyMap(),
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

@OptIn(ExperimentalFoundationApi::class)
class EditorViewModel(
    documentKey: String,
) : ViewModel() {
    private val client = Client(
        options = Client.Options(
            apiKey = BuildConfig.YORKIE_API_KEY,
        ),
        host = BuildConfig.YORKIE_SERVER_URL,
    )

    private val document = Document(
        Document.Key(documentKey),
    )

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    val textFieldState = TextFieldState()

    private var myClientId = ActorID("")

    private val gson = GsonBuilder()
        .registerTypeAdapter(ActorID::class.java, ActorIDAdapter())
        .create()

    init {
        viewModelScope.launch {
            connectAndAttachDocument()
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

                            handleSelectEvent(
                                textFieldState.selection.start,
                                textFieldState.selection.end,
                            )
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

            launch {
                snapshotFlow { textFieldState.selection }.collectLatest {
                    handleSelectEvent(textFieldState.selection.start, textFieldState.selection.end)
                }
            }
        }
    }

    private suspend fun connectAndAttachDocument() {
        try {
            val activateAsyncResult = client.activateAsync().await()
            if (!activateAsyncResult.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to connect to Yorkie server",
                )
                Timber.e(
                    "${this@EditorViewModel::class.java.name}#init: %s",
                    "Failed to connect to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${activateAsyncResult.exceptionOrNull()}",
                )
                return
            }

            val attachAsyncResult = client.attachAsync(
                document,
                initialPresence = mapOf(
                    "username" to "\"${generateDisplayName()}\"",
                    "color" to "\"${randomColorString()}\"",
                ),
            ).await()
            if (!attachAsyncResult.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to attach document to Yorkie server",
                )
                Timber.e(
                    "${this@EditorViewModel::class.java.name}#init: %s",
                    "Failed to attach document ${document.key} " +
                        "to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${attachAsyncResult.exceptionOrNull()}",
                )
                return
            }

            Timber.i(
                "${this@EditorViewModel::class.java.name}#init: %s",
                "Connected to Yorkie server ${BuildConfig.YORKIE_SERVER_URL} " +
                    "with document ${document.key}",
            )

            if (document.getRoot().getAsOrNull<JsonText>(CONTENT) == null) {
                document.updateAsync { root, _ ->
                    root.setNewText(CONTENT).apply {
                        edit(0, 0, "\n")
                    }
                }.await()
            }

            client.syncAsync().await()
            _uiState.update { it.copy(isLoading = false) }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred",
                )
            }
            Timber.e(
                "${this@EditorViewModel::class.java.name}#init: %s",
                e.message ?: "Unknown error occurred",
            )
        }
    }

    /**
     * Syncs the text content snapshot from the Yorkie document to the UI state
     */
    private suspend fun syncTextSnapShot() {
        val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)
        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        var idx = 0
        content?.values?.forEach { textWithAttributes ->
            newStyleOperations.add(
                OperationInfo.StyleOpInfo(
                    from = idx,
                    to = idx + textWithAttributes.text.length,
                    attributes = textWithAttributes.attributes,
                ),
            )
            idx += textWithAttributes.text.length
        }
        textFieldState.edit {
            val newContent = content.toString().dropLineBreakLast()
            replace(0, length, newContent)
            placeCursorAtEnd()
        }
        _uiState.update {
            it.copy(
                styleOperations = newStyleOperations,
            )
        }
    }

    private suspend fun syncTextRemoteChanged(changeInfo: Document.Event.ChangeInfo) {
        val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)
        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        var idx = 0
        content?.values?.forEach { textWithAttributes ->
            newStyleOperations.add(
                OperationInfo.StyleOpInfo(
                    from = idx,
                    to = idx + textWithAttributes.text.length,
                    attributes = textWithAttributes.attributes,
                ),
            )
            idx += textWithAttributes.text.length
        }

        // Adjust local text selection based on remote edits
        val editOperations = changeInfo.operations
            .filterIsInstance<OperationInfo.EditOpInfo>()
        var currentFrom = textFieldState.selection.start
        var currentTo = textFieldState.selection.end
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

        textFieldState.edit {
            replace(0, length, content.toString().dropLineBreakLast())
            selection = TextRange(
                start = currentFrom.coerceIn(0, length),
                end = currentTo.coerceIn(0, length),
            )
        }

        _uiState.update { state ->
            state.copy(
                styleOperations = newStyleOperations,
                selectionPeers = document.presences.value.mapValues {
                    it.value.mapToSelection(it.key)
                },
            )
        }
    }

    private suspend fun Map<String, String>.mapToSelection(actorID: ActorID): Selection? {
        if (actorID == myClientId) return null

        // Deserialize to DTO first (ProGuard-safe with @SerializedName)
        val jsonArray = gson.fromJson<List<RgaTreeSplitPosDto>>(
            this["selection"],
            object : TypeToken<List<RgaTreeSplitPosDto>>() {}.type,
        )
        return try {
            if (jsonArray != null && jsonArray.size >= 2) {
                // Convert DTO to SDK struct
                val fromPos = jsonArray[0].toRgaTreeSplitPosStruct()
                val toPos = jsonArray[1].toRgaTreeSplitPosStruct()

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

    fun editContent(changes: TextFieldBuffer.ChangeList, newContent: CharSequence) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                changes.forEachChange { range, originalRange ->
                    val content = if (range.start == range.end) {
                        ""
                    } else {
                        newContent.substring(range.start, range.end)
                    }

                    val styles = ArrayList<EditStyle>()
                    if (content.isNotEmpty()) {
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
                    }

                    root.getAs<JsonText>(CONTENT).edit(
                        fromIndex = originalRange.start,
                        toIndex = originalRange.end,
                        content = content,
                        attributes = styles.associate {
                            it.key to "true"
                        },
                    )
                }
            }.await()
            syncTextStyle()
        }
    }

    private suspend fun syncTextStyle() {
        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        var idx = 0
        val t = StringBuilder()
        document.getRoot().getAsOrNull<JsonText>(CONTENT)?.values?.forEach { textWithAttributes ->
            t.append(textWithAttributes.text)
            newStyleOperations.add(
                OperationInfo.StyleOpInfo(
                    from = idx,
                    to = idx + textWithAttributes.text.length,
                    attributes = textWithAttributes.attributes,
                ),
            )
            idx += textWithAttributes.text.length
        }
        _uiState.update {
            it.copy(
                styleOperations = newStyleOperations,
            )
        }
    }

    private fun handleSelectEvent(from: Int, to: Int) {
        viewModelScope.launch {
            // Update Yorkie presence
            document.updateAsync { root, presence ->
                val range = root.getAs<JsonText>(CONTENT)
                    .indexRangeToPosRange(from to to)?.toList().orEmpty()
                // Convert to DTO for ProGuard-safe serialization
                val rangeDto = range.map { RgaTreeSplitPosDto.fromRgaTreeSplitPosStruct(it) }
                presence.put(mapOf("selection" to gson.toJson(rangeDto)))
            }.await()
        }
    }

    private fun toggleStyle(
        style: EditStyle,
        currentValue: Boolean,
        updateState: (EditorUiState) -> EditorUiState,
    ) {
        _uiState.update(updateState)

        val textSelection = textFieldState.selection
        if (textSelection.start < textSelection.end) {
            viewModelScope.launch {
                document.updateAsync { root, _ ->
                    root.getAs<JsonText>(CONTENT).style(
                        fromIndex = textSelection.start,
                        toIndex = textSelection.end,
                        attributes = mapOf(
                            style.key to if (!currentValue) "true" else "null",
                        ),
                    )
                }.await()
                syncTextStyle()
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

    /**
     * Generates a user-friendly display name from a clientID.
     * This creates a consistent name for each client based on their ID.
     */
    private fun generateDisplayName(): String {
        // List of friendly adjectives and nouns for generating names
        val adjectives = listOf(
            "Happy", "Swift", "Bright", "Cool", "Smart", "Quick", "Bold", "Calm",
            "Wise", "Kind", "Brave", "Sharp", "Gentle", "Strong", "Clever", "Warm",
        )

        val nouns = listOf(
            "Fox", "Eagle", "Tiger", "Wolf", "Bear", "Lion", "Hawk", "Owl",
            "Deer", "Rabbit", "Dolphin", "Whale", "Shark", "Panda", "Koala", "Penguin",
        )

        return "${adjectives.random()} ${nouns.random()}"
    }

    private fun String.dropLineBreakLast() = if (endsWith("\n")) {
        dropLast(1)
    } else {
        this
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

        fun provideFactory(documentKey: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    documentKey ?: throw IllegalArgumentException("Document Key is not found")
                    val decodedDocumentKey = URLDecoder.decode(documentKey, UTF_8.name())
                    return EditorViewModel(decodedDocumentKey) as T
                }
            }
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
