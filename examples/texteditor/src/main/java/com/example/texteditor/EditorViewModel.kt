package com.example.texteditor

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
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
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import dev.yorkie.document.RgaTreeSplitPosStruct as TextPosStructure

class EditorViewModel(private val client: Client) : ViewModel() {
    // MVI State Management
    private val _state = MutableStateFlow(TextEditorContract.State())
    val state: StateFlow<TextEditorContract.State> = _state.asStateFlow()

    // Side Effects Channel
    private val _effects = Channel<TextEditorContract.Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    // Internal components
    private val document = Document(Document.Key(DOCUMENT_KEY))
    private val gson = Gson()

    // Legacy support for backward compatibility
    private val _selectionColors = mutableMapOf<ActorID, Int>()
    val selectionColors: Map<ActorID, Int>
        get() = _selectionColors

    init {
        observeDocumentEvents()
        handleIntent(TextEditorContract.Intent.InitializeEditor())
    }

    /**
     * Main intent handler - processes all user intents
     */
    fun handleIntent(intent: TextEditorContract.Intent) {
        viewModelScope.launch {
            when (intent) {
                is TextEditorContract.Intent.InitializeEditor -> {
                    updateState { copy(content = intent.initialContent, isLoading = true) }
                    initializeDocument()
                }

                is TextEditorContract.Intent.TextChanged -> {
                    handleTextChange(intent.changeRange)
                }

                is TextEditorContract.Intent.SelectionChanged -> {
                    updateState {
                        copy(localSelection = TextEditorContract.State.TextSelection(intent.start, intent.end))
                    }
                    handleSelectionChange(intent.start, intent.end)
                }

                TextEditorContract.Intent.StartHangulComposition -> {
                    updateState { copy(isComposing = true) }
                    client.changeSyncMode(document, RealtimePushOnly)
                }

                TextEditorContract.Intent.EndHangulComposition -> {
                    updateState { copy(isComposing = false) }
                    client.changeSyncMode(document, Realtime)
                }

                is TextEditorContract.Intent.ApplyRemoteChange -> {
                    updateState { copy(content = intent.text) }
                }

                TextEditorContract.Intent.SyncText -> {
                    syncText()
                }

                TextEditorContract.Intent.Disconnect -> {
                    disconnect()
                }

                is TextEditorContract.Intent.RemovePeer -> {
                    removePeer(intent.actorID)
                }
            }
        }
    }

    private fun updateState(update: TextEditorContract.State.() -> TextEditorContract.State) {
        _state.value = _state.value.update()
    }

    private fun emitEffect(effect: TextEditorContract.Effect) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }

    private fun initializeDocument() {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }

            try {
                if (document.getRoot().getAsOrNull<JsonText>(CONTENT) == null) {
                    document.updateAsync { root, _ ->
                        root.setNewText(CONTENT)
                    }.await()
                }

                if (client.activateAsync().await().isSuccess &&
                    client.attachAsync(document).await().isSuccess
                ) {
                    client.syncAsync().await()
                    updateState { copy(isConnected = true, isLoading = false) }
                } else {
                    updateState { copy(error = "Failed to connect to Yorkie server", isLoading = false) }
                }
            } catch (e: Exception) {
                updateState { copy(error = e.message, isLoading = false) }
                emitEffect(TextEditorContract.Effect.ShowError(e.message ?: "Unknown error"))
            }
        }
    }

    private fun observeDocumentEvents() {
        viewModelScope.launch {
            document.events.collect { event ->
                when (event) {
                    is Document.Event.Snapshot -> {
                        syncText()
                    }

                    is Document.Event.RemoteChange -> {
                        handleRemoteChanges(event.changeInfo)
                    }

                    is PresenceChanged.Others.PresenceChanged -> {
                        event.changed.handlePeerSelection()
                    }

                    is PresenceChanged.Others.Unwatched -> {
                        removePeer(event.changed.actorID)
                    }

                    else -> {}
                }
            }
        }
    }

    private fun syncText() {
        viewModelScope.launch {
            try {
                val content = document.getRoot().getAsOrNull<JsonText>(CONTENT)?.toString().orEmpty()
                updateState { copy(content = content) }
            } catch (e: Exception) {
                emitEffect(TextEditorContract.Effect.ShowError("Failed to sync text: ${e.message}"))
            }
        }
    }

    private fun handleRemoteChanges(changeInfo: Document.Event.ChangeInfo) {
        // Apply remote text changes without triggering local change events
        changeInfo.operations.filterIsInstance<OperationInfo.EditOpInfo>()
            .forEach {
                updateState { copy(content = it) }
            }
        // Note: changeInfo contains the actual change details but we sync the entire document for simplicity
    }

    private suspend fun PresenceInfo.handlePeerSelection() {
        runCatching {
            delay(500)
            val jsonArray = JSONArray(presence["selection"] ?: return)
            val fromPos = gson.fromJson(jsonArray.getString(0), TextPosStructure::class.java) ?: return
            val toPos = gson.fromJson(jsonArray.getString(1), TextPosStructure::class.java) ?: return
            val (from, to) = document.getRoot().getAs<JsonText>(CONTENT)
                .posRangeToIndexRange(fromPos to toPos)

            val color = getPeerSelectionComposeColor(actorID)
            val peerSelection = TextEditorContract.State.PeerSelection(
                range = from..to,
                color = color
            )

            updateState {
                copy(peerSelections = peerSelections + (actorID to peerSelection))
            }
        }
    }

    private fun handleTextChange(changeRange: TextEditorContract.TextRange) {
        viewModelScope.launch {
            try {
                document.updateAsync { root, _ ->
                    val range = root.getAs<JsonText>(CONTENT).edit(
                        changeRange.start,
                        changeRange.end,
                        changeRange.text
                    )
                    range?.let {
                        handleSelectionChange(range.first, range.second)
                    }
                }.await()
            } catch (e: Exception) {
                emitEffect(TextEditorContract.Effect.ShowError("Failed to apply text change: ${e.message}"))
            }
        }
    }

    private fun handleSelectionChange(from: Int, to: Int) {
        viewModelScope.launch {
            try {
                document.updateAsync { root, presence ->
                    val range = root.getAs<JsonText>(CONTENT)
                        .indexRangeToPosRange(from to to)?.toList()
                    presence.put(mapOf("selection" to gson.toJson(range)))
                }.await()
            } catch (e: Exception) {
                emitEffect(TextEditorContract.Effect.ShowError("Failed to update selection: ${e.message}"))
            }
        }
    }

    private fun removePeer(actorID: ActorID) {
        _selectionColors.remove(actorID)
        updateState {
            copy(peerSelections = peerSelections - actorID)
        }
    }

    private fun disconnect() {
        viewModelScope.launch {
            try {
                TerminationScope.launch {
                    client.detachAsync(document).await()
                    client.deactivateAsync().await()
                }
                updateState { copy(isConnected = false) }
            } catch (e: Exception) {
                emitEffect(TextEditorContract.Effect.ShowError("Failed to disconnect: ${e.message}"))
            }
        }
    }

    @ColorInt
    fun getPeerSelectionColor(actorID: ActorID): Int {
        return _selectionColors.getOrPut(actorID) {
            Color.argb(51, Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
        }
    }

    private fun getPeerSelectionComposeColor(actorID: ActorID): androidx.compose.ui.graphics.Color {
        val androidColor = getPeerSelectionColor(actorID)
        return androidx.compose.ui.graphics.Color(androidColor)
    }

    // Legacy support methods for backward compatibility
    fun legacySyncText() {
        handleIntent(TextEditorContract.Intent.SyncText)
    }

    fun removeUnwatchedPeerSelectionInfo(actorID: ActorID) {
        handleIntent(TextEditorContract.Intent.RemovePeer(actorID))
    }

    // Compose helper properties for backward compatibility
    val composeSelectionHighlights: Map<ActorID, Pair<Int, Int>>
        get() = state.value.peerSelections.mapValues { (_, selection) ->
            selection.range.first to selection.range.last
        }

    val composeSelectionColors: Map<ActorID, androidx.compose.ui.graphics.Color>
        get() = state.value.peerSelections.mapValues { (_, selection) ->
            selection.color
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
        private const val CONTENT = "content"

        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
}
