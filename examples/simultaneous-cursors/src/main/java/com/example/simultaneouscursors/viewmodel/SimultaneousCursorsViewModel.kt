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
                        println("Hahaha123 $event")
                        when (event) {
                            is PresenceChanged.Others.PresenceChanged -> {
                                updateClientPresence(event.changed.actorID.value, event.changed.presence)
                            }
                            is PresenceChanged.Others.Unwatched -> {
                                removeClientPresence(event.changed.actorID.value)
                            }
                            is PresenceChanged.MyPresence.PresenceChanged -> {
                                updateClientPresence(event.changed.actorID.value, event.changed.presence)
                            }
                            else -> {}
                        }
                    }
                }

                // Attach document with initial presence
                yorkieClient.attachAsync(
                    document = document,
                    initialPresence = mapOf(
                        "cursor" to gson.toJson(
                            mapOf(
                                "xPos" to 0,
                                "yPos" to 0,
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
            presenceData["cursorShape"] ?: "cursor"
        )

        val xPos = presenceData["cursorX"]?.toFloatOrNull() ?: 0f
        val yPos = presenceData["cursorY"]?.toFloatOrNull() ?: 0f
        val pointerDown = presenceData["pointerDown"]?.toBooleanStrictOrNull() ?: false

        val cursorPresence = CursorPresence(
            cursorShape = cursorShape,
            cursor = CursorPosition(xPos, yPos),
            pointerDown = pointerDown
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
                presence = presence
            )
        }
    }

    fun updateCursorShape(cursorShape: CursorShape) {
        selectedCursorShape = cursorShape
        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(mapOf("cursorShape" to cursorShape.iconName))
            }.await()
        }
    }

    fun updateCursorPosition(x: Float, y: Float) {
        viewModelScope.launch {
            document.updateAsync { _, presence ->
                presence.put(mapOf(
                    "cursor" to gson.toJson(
                        mapOf(
                            "xPos" to x,
                            "yPos" to y,
                        ),
                    ),
                ))
            }.await()
        }
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
