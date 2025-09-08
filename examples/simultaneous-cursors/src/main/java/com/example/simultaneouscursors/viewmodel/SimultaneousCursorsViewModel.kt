package com.example.simultaneouscursors.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simultaneouscursors.BuildConfig
import com.example.simultaneouscursors.model.ClientPresence
import com.example.simultaneouscursors.model.CursorPosition
import com.example.simultaneouscursors.model.CursorPresence
import com.example.simultaneouscursors.model.CursorShape
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.util.createSingleThreadDispatcher
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol

class SimultaneousCursorsViewModel : ViewModel() {

    var clients by mutableStateOf<List<ClientPresence>>(emptyList())
        private set

    var selectedCursorShape by mutableStateOf(CursorShape.CURSOR)
        private set

    private val clientPresences = mutableMapOf<String, CursorPresence>()

    // Current screen dimensions
    private var currentScreenWidth: Float = 0f
    private var currentScreenHeight: Float = 0f

    private val unaryClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val gson = GsonBuilder().create()

    private val yorkieClient = Client(
        options = Client.Options(
            apiKey = BuildConfig.YORKIE_API_KEY,
        ),
        host = BuildConfig.YORKIE_SERVER_URL,
        unaryClient = unaryClient,
        streamClient = unaryClient,
        dispatcher = createSingleThreadDispatcher("YorkieClient"),
    )

    private val document = Document(Document.Key("simultaneous-cursors"))

    init {
        setupYorkieConnection()
    }

    private fun setupYorkieConnection() {
        viewModelScope.launch {
            try {
                yorkieClient.activateAsync().await()

                // Subscribe to presence changes
                launch {
                    document.events.filterIsInstance<PresenceChanged>().collect { event ->
                        when (event) {
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
                                updateClientPresence(
                                    event.changed.actorID.value,
                                    event.changed.presence,
                                )
                            }

                            else -> {}
                        }
                    }
                }

                // Attach document with initial presence
                yorkieClient.attachAsync(
                    document = document,
                    initialPresence = mapOf(
                        "cursorShape" to "\"cursor\"",
                        "cursor" to gson.toJson(
                            mapOf(
                                "xPos" to 0,
                                "yPos" to 0,
                                "viewWidth" to 0,
                                "viewHeight" to 0,
                            ),
                        ),
                        "pointerDown" to "false",
                    ),
                ).await()

                // Initial clients list is empty
            } catch (e: Exception) {
                // Handle connection error
                e.printStackTrace()
            }
        }
    }

    private fun updateClientPresence(clientId: String, presenceData: Map<String, String>) {
        val cursorShape = CursorShape.fromString(
            gson.fromJson(presenceData["cursorShape"], String::class.java),
        )

        val cursor: Map<String, *> = gson.fromJson(
            presenceData["cursor"],
            object : TypeToken<Map<String, *>>() {}.type,
        )
        val rawXPos = (cursor["xPos"] as? Double) ?: 0.0
        val rawYPos = (cursor["yPos"] as? Double) ?: 0.0
        val otherClientViewWidth = (cursor["viewWidth"] as? Double) ?: 0.0
        val otherClientViewHeight = (cursor["viewHeight"] as? Double) ?: 0.0
        val pointerDown = presenceData["pointerDown"]?.toBooleanStrictOrNull() ?: false

        // Convert coordinates if viewWidth and viewHeight are provided (from other clients)
        val (xPos, yPos) = if (otherClientViewWidth > 0 && otherClientViewHeight > 0 &&
            currentScreenWidth > 0 && currentScreenHeight > 0
        ) {
            // Convert from other client's screen dimensions to current screen dimensions
            val normalizedX = rawXPos / otherClientViewWidth
            val normalizedY = rawYPos / otherClientViewHeight
            val convertedX = normalizedX * currentScreenWidth
            val convertedY = normalizedY * currentScreenHeight
            Pair(convertedX, convertedY)
        } else {
            // Use raw coordinates if no conversion needed (same client or no dimensions available)
            Pair(rawXPos, rawYPos)
        }

        val cursorPresence = CursorPresence(
            cursorShape = cursorShape,
            cursor = CursorPosition(xPos, yPos),
            pointerDown = pointerDown,
        )

        clientPresences[clientId] = cursorPresence
        updateClientsList()
    }

    private fun removeClientPresence(clientId: String) {
        clientPresences.remove(clientId)
        updateClientsList()
    }

    private fun updateClientsList() {
        clients = clientPresences.map { (clientId, presence) ->
            ClientPresence(
                clientID = clientId,
                presence = presence,
            )
        }
    }

    fun updateCursorShape(cursorShape: CursorShape) {
        selectedCursorShape = cursorShape
        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(mapOf("cursorShape" to gson.toJson(cursorShape.iconName)))
            }.await()
        }
    }

    fun updateCursorPosition(x: Float, y: Float) {
        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(
                    mapOf(
                        "cursor" to gson.toJson(
                            mapOf(
                                "xPos" to x,
                                "yPos" to y,
                                "viewWidth" to currentScreenWidth,
                                "viewHeight" to currentScreenHeight,
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

    fun updatePointerDown(isDown: Boolean) {
        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(mapOf("pointerDown" to isDown.toString()))
            }.await()
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try {
                yorkieClient.detachAsync(document).await()
                yorkieClient.deactivateAsync().await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
