package com.example.simultaneouscursors.ui.simultaneouscursors

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.simultaneouscursors.BuildConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.util.createSingleThreadDispatcher
import java.net.URLDecoder
import kotlin.random.Random
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import timber.log.Timber

data class SimultaneousCursorsUiState(
    val clients: List<ClientPresence> = emptyList(),
    val selectedCursorShape: CursorShape = CursorShape.CURSOR,
    val currentDrawingLinePreview: List<Offset> = emptyList(),
    val activeDrawingLines: Map<String, List<Offset>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SimultaneousCursorsViewModel(
    documentKey: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SimultaneousCursorsUiState(
            isLoading = true,
        ),
    )
    val uiState: StateFlow<SimultaneousCursorsUiState> = _uiState.asStateFlow()

    private val clientPresences = mutableMapOf<String, CursorPresence>()
    private val clientActiveDrawingLines = mutableMapOf<String, MutableList<Offset>>()

    // Current screen dimensions
    private var currentScreenWidth: Float = 0f
    private var currentScreenHeight: Float = 0f

    // Current drawing state
    private var currentDrawingLine: MutableList<Offset> = mutableListOf()
    private var isDrawing: Boolean = false

    private var myClientId = ""

    private val unaryClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val gson = GsonBuilder().create()

    private val client = Client(
        options = Client.Options(
            apiKey = BuildConfig.YORKIE_API_KEY,
        ),
        host = BuildConfig.YORKIE_SERVER_URL,
        unaryClient = unaryClient,
        streamClient = unaryClient,
        dispatcher = createSingleThreadDispatcher("YorkieClient"),
    )

    private val document = Document(Document.Key(documentKey))

    init {
        setupYorkieConnection()
    }

    private fun setupYorkieConnection() {
        viewModelScope.launch {
            connectAndAttachDocument()
        }

        viewModelScope.launch {
            launch {
                document.events.filterIsInstance<PresenceChanged>().collect { event ->
                    when (event) {
                        is PresenceChanged.MyPresence.Initialized -> {
                            event.initialized.forEach { (key, value) ->
                                updateClientPresence(
                                    key.value,
                                    value,
                                )
                            }
                        }

                        is PresenceChanged.Others.PresenceChanged -> {
                            updateClientPresence(
                                event.changed.actorID.value,
                                event.changed.presence,
                            )
                        }

                        is PresenceChanged.Others.Unwatched -> {
                            removeClientPresence(event.changed.actorID.value)
                        }

                        is PresenceChanged.MyPresence.PresenceChanged -> {
                            myClientId = event.changed.actorID.value
                            updateClientPresence(
                                event.changed.actorID.value,
                                event.changed.presence,
                            )
                        }

                        else -> {}
                    }
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
                    "${this@SimultaneousCursorsViewModel::class.java.name}#init: %s",
                    "Failed to connect to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${activateAsyncResult.exceptionOrNull()}",
                )
                return
            }

            val attachAsyncResult = client.attachAsync(
                document = document,
                initialPresence = mapOf(
                    "name" to "\"${generateDisplayName()}\"",
                    "color" to "\"${randomColorString()}\"",
                    "cursorShape" to "\"cursor\"",
                    "cursor" to gson.toJson(
                        mapOf(
                            "xPos" to 0.0,
                            "yPos" to 0.0,
                        ),
                    ),
                    "pointerDown" to "false",
                ),
            ).await()
            if (!attachAsyncResult.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to attach document to Yorkie server",
                )
                Timber.e(
                    "${this@SimultaneousCursorsViewModel::class.java.name}#init: %s",
                    "Failed to attach document ${document.key} " +
                        "to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${attachAsyncResult.exceptionOrNull()}",
                )
                return
            }

            Timber.i(
                "${this@SimultaneousCursorsViewModel::class.java.name}#init: %s",
                "Connected to Yorkie server ${BuildConfig.YORKIE_SERVER_URL} " +
                    "with document ${document.key}",
            )

            _uiState.value = _uiState.value.copy(
                isLoading = false,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Failed to connect: ${e.message}",
            )
            Timber.e(
                "${this@SimultaneousCursorsViewModel::class.java.name}#init: %s",
                e.message ?: "Unknown error occurred",
            )
        }
    }

    private fun updateClientPresence(clientId: String, presenceData: Map<String, String>) {
        val name = try {
            gson.fromJson(presenceData["name"], String::class.java)
        } catch (e: JsonSyntaxException) {
            null
        }
        val color = try {
            gson.fromJson(presenceData["color"], String::class.java)
        } catch (e: JsonSyntaxException) {
            null
        }
        val cursorShape = CursorShape.fromString(
            gson.fromJson(presenceData["cursorShape"], String::class.java),
        )

        val cursor: Map<String, *> = gson.fromJson(
            presenceData["cursor"],
            object : TypeToken<Map<String, *>>() {}.type,
        )
        val normalizedXPos = (cursor["xPos"] as? Double) ?: 0.0
        val normalizedYPos = (cursor["yPos"] as? Double) ?: 0.0
        val pointerDown = presenceData["pointerDown"]?.toBooleanStrictOrNull() ?: false

        // Convert normalized coordinates (0-1 range) to current screen coordinates
        val xPos = if (currentScreenWidth > 0) {
            normalizedXPos * currentScreenWidth
        } else {
            normalizedXPos
        }
        val yPos = if (currentScreenHeight > 0) {
            normalizedYPos * currentScreenHeight
        } else {
            normalizedYPos
        }

        val cursorPresence = CursorPresence(
            name = name,
            cursorShape = cursorShape,
            cursor = CursorPosition(xPos, yPos),
            pointerDown = pointerDown,
            isMyself = clientId == myClientId,
            color = color,
        )

        // Get previous presence to check for state changes
        val previousPresence = clientPresences[clientId]
        clientPresences[clientId] = cursorPresence

        // Handle pen drawing logic for other clients
        if (cursorPresence.cursorShape == CursorShape.PEN) {
            if (cursorPresence.pointerDown) {
                // Client is drawing - add current position to their active line
                val activeDrawingLine = clientActiveDrawingLines.getOrPut(
                    clientId,
                ) { mutableListOf() }
                activeDrawingLine.add(Offset(xPos.toFloat(), yPos.toFloat()))
            } else if (previousPresence?.pointerDown == true && !cursorPresence.pointerDown) {
                // Client finished drawing - clear the active line (don't save it)
                clientActiveDrawingLines[clientId] = mutableListOf() // Clear active line
            }
        }

        // Update active drawing lines state
        updateUiState()

        updateClientsList()
    }

    private fun removeClientPresence(clientId: String) {
        clientPresences.remove(clientId)
        clientActiveDrawingLines.remove(clientId)
        updateUiState()
        updateClientsList()
    }

    private fun updateClientsList() {
        val clientsList = clientPresences.map { (clientId, presence) ->
            ClientPresence(
                clientID = clientId,
                presence = presence,
            )
        }
        _uiState.value = _uiState.value.copy(clients = clientsList)
    }

    private fun updateUiState() {
        _uiState.value = _uiState.value.copy(
            activeDrawingLines = clientActiveDrawingLines.toMap(),
        )
    }

    fun updateCursorShape(cursorShape: CursorShape) {
        _uiState.value = _uiState.value.copy(selectedCursorShape = cursorShape)
        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(mapOf("cursorShape" to gson.toJson(cursorShape.iconName)))
            }.await()
        }
    }

    private fun updateCursorPosition(x: Float, y: Float) {
        // Add point to current drawing line if pen is being used and pointer is down
        if (_uiState.value.selectedCursorShape == CursorShape.PEN && isDrawing) {
            currentDrawingLine.add(Offset(x, y))
            _uiState.value = _uiState.value.copy(
                currentDrawingLinePreview = currentDrawingLine.toList(),
            )
        }

        // Normalize coordinates to 0-1 range before sending to server
        val normalizedX = if (currentScreenWidth > 0) (x / currentScreenWidth).toDouble() else 0.0
        val normalizedY = if (currentScreenHeight > 0) (y / currentScreenHeight).toDouble() else 0.0

        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(
                    mapOf(
                        "cursor" to gson.toJson(
                            mapOf(
                                "xPos" to normalizedX,
                                "yPos" to normalizedY,
                            ),
                        ),
                    ),
                )
            }.await()
        }
    }

    fun updateScreenDimensions(width: Float, height: Float) {
        currentScreenWidth = width
        currentScreenHeight = height
    }

    fun startDragging(offset: Offset) {
        if (_uiState.value.selectedCursorShape == CursorShape.PEN) {
            updateCursorPosition(offset.x, offset.y)
            viewModelScope.launch {
                document.updateAsync { _, presence ->
                    presence.put(mapOf("pointerDown" to "true"))
                }.await()
            }
        } else {
            updateCursorPosition(offset.x, offset.y)
            viewModelScope.launch {
                document.updateAsync { _, presence ->
                    presence.put(mapOf("pointerDown" to "false"))
                }.await()
            }
        }
    }

    fun endDragging() {
        if (_uiState.value.selectedCursorShape == CursorShape.PEN) {
            viewModelScope.launch {
                document.updateAsync { _, presence ->
                    presence.put(mapOf("pointerDown" to "false"))
                }.await()
            }
        }
    }

    fun pressDown(offset: Offset) {
        updateCursorPosition(offset.x, offset.y)
        if (_uiState.value.selectedCursorShape != CursorShape.PEN) {
            viewModelScope.launch {
                document.updateAsync { _, presence ->
                    presence.put(mapOf("pointerDown" to "true"))
                }.await()
            }
        }
    }

    fun release() {
        if (_uiState.value.selectedCursorShape != CursorShape.PEN) {
            viewModelScope.launch {
                document.updateAsync { _, presence ->
                    presence.put(mapOf("pointerDown" to "false"))
                }.await()
            }
        }
    }

    override fun onCleared() {
        TerminationScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
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

    private fun randomColorString(): String {
        val hue = Random.nextFloat() * 360f
        val saturation = 0.6f + Random.nextFloat() * 0.4f
        val value = 0.25f + Random.nextFloat() * 0.25f

        val color = Color.hsv(hue, saturation, value)

        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        return String.format("#%02X%02X%02X", r, g, b)
    }

    companion object {
        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

        fun provideFactory(documentKey: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    documentKey ?: throw IllegalArgumentException("Document Key is not found")
                    val decodedDocumentKey = URLDecoder.decode(documentKey, UTF_8.name())
                    return SimultaneousCursorsViewModel(decodedDocumentKey) as T
                }
            }
    }
}
