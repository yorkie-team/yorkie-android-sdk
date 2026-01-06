package dev.yorkie.example.collaborativeeditdemo.ui.editor

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
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import dev.yorkie.collaborative.editing.CollaborativeEditConfig
import dev.yorkie.collaborative.editing.CollaborativeEditEvent
import dev.yorkie.collaborative.editing.CollaborativeEditPlugin
import dev.yorkie.collaborative.editing.EditorAdapter
import dev.yorkie.collaborative.editing.EditorChangeListener
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import dev.yorkie.example.collaborativeeditdemo.BuildConfig
import java.lang.reflect.Type
import java.net.URLDecoder
import kotlin.random.Random
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * UI State for the collaborative editor screen.
 */
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

/**
 * ViewModel for the collaborative editor demo screen.
 *
 * This demonstrates real-time collaborative text editing using the CollaborativeEditPlugin,
 * showing how to integrate the plugin with a text editor.
 */
@OptIn(ExperimentalFoundationApi::class)
class EditorViewModel(
    documentKey: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    val textFieldState = TextFieldState()

    private var myClientId = ActorID("")

    private val gson = GsonBuilder()
        .registerTypeAdapter(ActorID::class.java, ActorIDAdapter())
        .create()

    /**
     * Editor adapter that bridges the plugin with this ViewModel.
     */
    private val editorAdapter = object : EditorAdapter {
        private var listener: EditorChangeListener? = null

        override fun onInitialized(tree: JsonTree?) {
            Timber.d("EditorAdapter: Initialized")
            viewModelScope.launch {
                syncTextFromPlugin()
            }
        }

        override fun onSnapshot(tree: JsonTree?) {
            Timber.d("EditorAdapter: Snapshot received")
            viewModelScope.launch {
                syncTextFromPlugin()
            }
        }

        override fun onRemoteChange(operations: List<OperationInfo>) {
            Timber.d("EditorAdapter: Remote change with ${operations.size} operations")
            viewModelScope.launch {
                syncTextRemoteChanged(operations)
            }
        }

        override fun subscribeToChanges(listener: EditorChangeListener) {
            this.listener = listener
        }

        override fun unsubscribeFromChanges() {
            this.listener = null
        }

        fun notifyLocalChange(info: Any) {
            listener?.onLocalChange(info)
        }

        fun notifySelectionChange(from: Int, to: Int) {
            listener?.onSelectionChange(from, to)
        }
    }

    /**
     * The collaborative editing plugin instance.
     */
    private val plugin = CollaborativeEditPlugin(
        config = CollaborativeEditConfig(
            serverUrl = BuildConfig.YORKIE_SERVER_URL,
            documentKey = documentKey,
            apiKey = BuildConfig.YORKIE_API_KEY,
            initialPresence = mapOf(
                "username" to "\"${generateDisplayName()}\"",
                "color" to "\"${randomColorString()}\"",
            ),
        ),
        editorAdapter = editorAdapter,
    )

    init {
        // Subscribe to plugin events
        viewModelScope.launch {
            plugin.events.collect { event ->
                handlePluginEvent(event)
            }
        }

        // Subscribe to text field selection changes
        viewModelScope.launch {
            snapshotFlow { textFieldState.selection }.collectLatest { selection ->
                handleSelectionChange(selection.start, selection.end)
            }
        }

        viewModelScope.launch {
            // Initialize the plugin
            plugin.initialize()
        }
    }

    private fun handlePluginEvent(event: CollaborativeEditEvent) {
        when (event) {
            is CollaborativeEditEvent.Lifecycle.Initialized -> {
                myClientId = event.actorId
            }

            is CollaborativeEditEvent.Lifecycle.Ready -> {
                viewModelScope.launch {
                    // Initialize text content if not exists
                    initializeTextIfNeeded()
                    plugin.sync()
                    syncTextFromPlugin()
                }
                _uiState.update { it.copy(isLoading = false) }
            }

            is CollaborativeEditEvent.Lifecycle.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = event.message,
                    )
                }
            }

            is CollaborativeEditEvent.Lifecycle.Destroyed -> {
                // Plugin destroyed
            }

            is CollaborativeEditEvent.ConnectionStatus.Connected -> {
                // Stream connected
            }

            is CollaborativeEditEvent.ConnectionStatus.Disconnected -> {
                // Stream disconnected
            }

            is CollaborativeEditEvent.SyncStatus.Synced -> {
                // Synced
            }

            is CollaborativeEditEvent.SyncStatus.SyncFailed -> {
                // Sync failed
            }

            is CollaborativeEditEvent.DocumentChanged.LocalChange -> {
                updateSelectionPeersFromPlugin()
            }

            is CollaborativeEditEvent.DocumentChanged.RemoteChange -> {
                // Handled by editorAdapter.onRemoteChange
            }

            is CollaborativeEditEvent.DocumentChanged.Snapshot -> {
                // Handled by editorAdapter.onSnapshot
            }

            is CollaborativeEditEvent.PresenceEvent.Initialized -> {
                updatePeersFromPresences(event.presences.values.toList())
                updateSelectionPeersFromPlugin()
            }

            is CollaborativeEditEvent.PresenceEvent.MyPresenceChanged -> {
                myClientId = event.presenceInfo.actorID
            }

            is CollaborativeEditEvent.PresenceEvent.OtherWatched -> {
                updatePeersFromPlugin()
            }

            is CollaborativeEditEvent.PresenceEvent.OtherUnwatched -> {
                removePeerSelection(event.presenceInfo.actorID)
                updatePeersFromPlugin()
            }

            is CollaborativeEditEvent.PresenceEvent.OtherPresenceChanged -> {
                viewModelScope.launch {
                    updatePeerSelection(event.presenceInfo.actorID, event.presenceInfo.presence)
                }
            }
        }
    }

    /**
     * Initialize text content in the document if it doesn't exist.
     */
    private suspend fun initializeTextIfNeeded() {
        val root = plugin.getRoot()
        if (root.getAsOrNull<JsonText>(CONTENT) == null) {
            plugin.updateDocument { r ->
                r.setNewText(CONTENT).apply {
                    edit(0, 0, "\n")
                }
            }
        }
    }

    /**
     * Sync text field content from the plugin's document.
     */
    private suspend fun syncTextFromPlugin() {
        val root = plugin.getRoot()
        val content = root.getAsOrNull<JsonText>(CONTENT) ?: return
        textFieldState.edit {
            val newContent = content.toString().dropLineBreakLast()
            replace(0, length, newContent)
            placeCursorAtEnd()
        }
        syncStyleOperations(content)
    }

    /**
     * Sync style operations from the text content.
     */
    private fun syncStyleOperations(content: JsonText) {
        val newStyleOperations = ArrayList<OperationInfo.StyleOpInfo>()
        var idx = 0
        content.values.forEach { textWithAttributes ->
            newStyleOperations.add(
                OperationInfo.StyleOpInfo(
                    from = idx,
                    to = idx + textWithAttributes.text.length,
                    attributes = textWithAttributes.attributes,
                ),
            )
            idx += textWithAttributes.text.length
        }
        _uiState.update { it.copy(styleOperations = newStyleOperations) }
    }

    /**
     * Sync text field when remote changes arrive.
     */
    private suspend fun syncTextRemoteChanged(operations: List<OperationInfo>) {
        val root = plugin.getRoot()
        val content = root.getAsOrNull<JsonText>(CONTENT) ?: return

        // Adjust local selection based on remote edits
        val editOperations = operations.filterIsInstance<OperationInfo.EditOpInfo>()
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

        syncStyleOperations(content)
        updateSelectionPeersFromPlugin()
    }

    /**
     * Handle local text edits and push to the plugin.
     */
    fun editContent(changes: TextFieldBuffer.ChangeList, newContent: CharSequence) {
        viewModelScope.launch {
            plugin.updateDocument { root ->
                val text = root.getAs<JsonText>(CONTENT)
                changes.forEachChange { range, originalRange ->
                    val content = if (range.start == range.end) {
                        ""
                    } else {
                        newContent.substring(range.start, range.end)
                    }
                    text.edit(
                        fromIndex = originalRange.start,
                        toIndex = originalRange.end,
                        content = content,
                    )
                }
            }
            editorAdapter.notifyLocalChange("text edit")
        }
    }

    /**
     * Toggle text formatting and apply to selection.
     */
    private fun toggleStyle(
        styleKey: String,
        currentValue: Boolean,
        updateState: (EditorUiState) -> EditorUiState,
    ) {
        _uiState.update(updateState)

        val selection = textFieldState.selection
        if (selection.start < selection.end) {
            viewModelScope.launch {
                plugin.updateDocument { root ->
                    root.getAs<JsonText>(CONTENT).style(
                        fromIndex = selection.start,
                        toIndex = selection.end,
                        attributes = mapOf(
                            styleKey to if (!currentValue) "true" else "null",
                        ),
                    )
                }
                syncStyleOperationsFromPlugin()
            }
        }
    }

    private suspend fun syncStyleOperationsFromPlugin() {
        val root = plugin.getRoot()
        val content = root.getAsOrNull<JsonText>(CONTENT) ?: return
        syncStyleOperations(content)
    }

    fun toggleBold() {
        toggleStyle("bold", _uiState.value.isBold) { it.copy(isBold = !it.isBold) }
    }

    fun toggleItalic() {
        toggleStyle("italic", _uiState.value.isItalic) { it.copy(isItalic = !it.isItalic) }
    }

    fun toggleUnderline() {
        toggleStyle(
            "underline",
            _uiState.value.isUnderline,
        ) { it.copy(isUnderline = !it.isUnderline) }
    }

    fun toggleStrikethrough() {
        toggleStyle("strike", _uiState.value.isStrikethrough) {
            it.copy(isStrikethrough = !it.isStrikethrough)
        }
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
     * Handle selection changes and update presence.
     */
    private fun handleSelectionChange(from: Int, to: Int) {
        viewModelScope.launch {
            val root = plugin.getRoot()
            val content = root.getAsOrNull<JsonText>(CONTENT) ?: return@launch

            val range = content.indexRangeToPosRange(from to to)?.toList().orEmpty()
            val rangeDto = range.map { RgaTreeSplitPosDto.fromRgaTreeSplitPosStruct(it) }
            plugin.updatePresence(mapOf("selection" to gson.toJson(rangeDto)))
            editorAdapter.notifySelectionChange(from, to)
        }
    }

    private suspend fun updatePeerSelection(actorID: ActorID, presence: Map<String, String>) {
        if (actorID == myClientId) return

        val selection = mapToSelection(actorID, presence)
        _uiState.update { state ->
            val updated = state.selectionPeers.toMutableMap()
            updated[actorID] = selection
            state.copy(selectionPeers = updated)
        }
    }

    private suspend fun mapToSelection(
        actorID: ActorID,
        presence: Map<String, String>,
    ): Selection? {
        if (actorID == myClientId) return null

        val jsonArray = try {
            gson.fromJson<List<RgaTreeSplitPosDto>>(
                presence["selection"],
                object : TypeToken<List<RgaTreeSplitPosDto>>() {}.type,
            )
        } catch (e: Exception) {
            null
        }

        return try {
            if (jsonArray != null && jsonArray.size >= 2) {
                val fromPos = jsonArray[0].toRgaTreeSplitPosStruct()
                val toPos = jsonArray[1].toRgaTreeSplitPosStruct()

                val root = plugin.getRoot()
                val content = root.getAsOrNull<JsonText>(CONTENT) ?: return null

                val (from, to) = content.posRangeToIndexRange(fromPos to toPos)
                val contentLength = content.toString().length
                val validFrom = from.coerceIn(0, contentLength)
                val validTo = to.coerceIn(0, contentLength)

                Selection(
                    name = gson.fromJson(presence["username"], String::class.java) ?: "Unknown",
                    from = validFrom,
                    to = validTo,
                    color = gson.fromJson(presence["color"], String::class.java) ?: "#808080",
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateSelectionPeersFromPlugin() {
        viewModelScope.launch {
            val presences = plugin.getPresences()
            val selectionMap = mutableMapOf<ActorID, Selection?>()
            presences.forEach { (actorId, presence) ->
                selectionMap[actorId] = mapToSelection(actorId, presence)
            }
            _uiState.update { it.copy(selectionPeers = selectionMap) }
        }
    }

    private fun removePeerSelection(actorID: ActorID) {
        _uiState.update { state ->
            val updated = state.selectionPeers.toMutableMap()
            updated.remove(actorID)
            state.copy(selectionPeers = updated)
        }
    }

    private fun updatePeersFromPlugin() {
        val presences = plugin.getPresences()
        val peers = presences.values.mapNotNull {
            try {
                gson.fromJson(it["username"], String::class.java)
            } catch (e: Exception) {
                null
            }
        }
        _uiState.update { it.copy(peers = peers) }
    }

    private fun updatePeersFromPresences(presenceList: List<Map<String, String>>) {
        val peers = presenceList.mapNotNull {
            try {
                gson.fromJson(it["username"], String::class.java)
            } catch (e: Exception) {
                null
            }
        }
        _uiState.update { it.copy(peers = peers) }
    }

    /**
     * Calculates the new selection position after a remote edit.
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
            currentTo <= editFrom -> currentFrom to currentTo
            currentFrom >= editTo -> (currentFrom + delta) to (currentTo + delta)
            else -> {
                val newPos = editFrom + insertedLength
                newPos to newPos
            }
        }
    }

    /**
     * Force sync with the server.
     */
    fun forceSync() {
        viewModelScope.launch {
            plugin.sync()
        }
    }

    private fun randomColorString(): String {
        val random = Random.Default
        val color = (0xFFFFFF and random.nextInt()).toString(16).padStart(6, '0')
        return "#$color"
    }

    private fun generateDisplayName(): String {
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

    private fun String.dropLineBreakLast() = if (endsWith("\n")) dropLast(1) else this

    override fun onCleared() {
        plugin.close()
        super.onCleared()
    }

    companion object {
        private const val CONTENT = "content"

        fun provideFactory(documentKey: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    documentKey ?: throw IllegalArgumentException("Document Key is required")
                    val decodedDocumentKey = URLDecoder.decode(documentKey, UTF_8.name())
                    @Suppress("UNCHECKED_CAST")
                    return EditorViewModel(decodedDocumentKey) as T
                }
            }
    }
}

private class ActorIDAdapter : JsonDeserializer<ActorID>, JsonSerializer<ActorID> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): ActorID = ActorID(json.asString)

    override fun serialize(
        src: ActorID,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonPrimitive(src.value)
}
