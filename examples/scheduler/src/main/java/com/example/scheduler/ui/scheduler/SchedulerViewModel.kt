package com.example.scheduler.ui.scheduler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.common.client.YorkieClient
import com.example.scheduler.BuildConfig
import com.google.gson.Gson
import dev.yorkie.core.Client
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Key
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.util.createSingleThreadDispatcher
import java.net.URLDecoder
import java.util.Calendar
import java.util.Date
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class SchedulerViewModel(
    documentKey: String,
) : ViewModel() {
    private val client = Client(
        options = Client.Options(
            apiKey = BuildConfig.YORKIE_API_KEY,
        ),
        host = BuildConfig.YORKIE_SERVER_URL,
        unaryClient = YorkieClient.createUnaryClient(),
        streamClient = YorkieClient.createStreamClient(),
        dispatcher = createSingleThreadDispatcher(
            "YorkieClient",
        ),
    )

    private val document = Document(Key(documentKey))

    private val _state = MutableStateFlow(
        SchedulerState(
            events = emptyList(),
            isLoading = true,
        ),
    )
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    private val randomPeers = listOf(
        "Alice", "Bob", "Carol", "Chuck", "Dave", "Erin", "Frank", "Grace",
        "Ivan", "Justin", "Matilda", "Oscar", "Steve", "Victor", "Zoe",
    )

    private val gson = Gson()

    init {
        viewModelScope.launch {
            connectAndAttachDocument()
        }

        viewModelScope.launch {
            // Subscribe to document changes
            launch {
                document.events.collect { event ->
                    when (event) {
                        is Document.Event.SyncStatusChanged.Synced -> {
                            val root = document.getRoot()
                                .getAsOrNull<JsonArray>(DOCUMENT_EVENTS_KEY)
                            root?.let(::updateEventsFromDocument)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }

            // Subscribe to presence changes
            launch {
                document.presences.collect { presences ->
                    val peerNames = presences.mapNotNull { (_, presence) ->
                        gson.fromJson(presence["userName"], String::class.java)
                    }
                    _state.value = _state.value.copy(peers = peerNames)
                }
            }
        }
    }

    private suspend fun connectAndAttachDocument() {
        try {
            val activateAsyncResult = client.activateAsync().await()
            if (!activateAsyncResult.isSuccess) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to connect to Yorkie server",
                )
                Timber.e(
                    "${this@SchedulerViewModel::class.java.name}#init: %s",
                    "Failed to connect to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${activateAsyncResult.exceptionOrNull()}",
                )
                return
            }

            val attachAsyncResult = client.attachAsync(
                document,
                initialPresence = mapOf("userName" to "\"${randomPeers.random()}\""),
            ).await()
            if (!attachAsyncResult.isSuccess) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to attach document to Yorkie server",
                )
                Timber.e(
                    "${this@SchedulerViewModel::class.java.name}#init: %s",
                    "Failed to attach document ${document.key} " +
                        "to Yorkie server ${BuildConfig.YORKIE_SERVER_URL}, " +
                        "Error: ${attachAsyncResult.exceptionOrNull()}",
                )
                return
            }

            Timber.i(
                "${this@SchedulerViewModel::class.java.name}#init: %s",
                "Connected to Yorkie server ${BuildConfig.YORKIE_SERVER_URL} " +
                    "with document ${document.key}",
            )

            _state.value = _state.value.copy(
                isLoading = false,
            )

            client.syncAsync().await()
            document.getRoot().getAsOrNull<JsonArray>(DOCUMENT_EVENTS_KEY)
                ?: run {
                    document.updateAsync { root, _ ->
                        root.setNewArray(DOCUMENT_EVENTS_KEY).apply {
                            putNewObject().apply {
                                set(
                                    "date",
                                    Event.parseDate(Date()).replace(Regex("\\d{2}$"), "01"),
                                )
                                set("text", "payday")
                            }
                            putNewObject().apply {
                                set(
                                    "date",
                                    Event.parseDate(Date()).replace(Regex("\\d{2}$"), "17"),
                                )
                                set("text", "Garry's birthday")
                            }
                        }
                    }.await()
                }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Failed to connect: ${e.message}",
            )
            Timber.e(
                "${this@SchedulerViewModel::class.java.name}#init: %s",
                e.message ?: "Unknown error occurred",
            )
        }
    }

    fun dispatch(action: SchedulerAction) {
        when (action) {
            is SchedulerAction.AddEvent -> addEvent(action.date, action.text)
            is SchedulerAction.UpdateEvent -> updateEvent(action.date, action.text)
            is SchedulerAction.DeleteEvent -> deleteEvent(action.date)
            is SchedulerAction.SetSelectedDate -> setSelectedDate(action.date)
            is SchedulerAction.SetEventText -> setEventText(action.text)
            is SchedulerAction.ShowEventDialog -> showEventDialog(action.show)
            is SchedulerAction.NavigateMonth -> navigateMonth(action.direction)
        }
    }

    private fun addEvent(date: String, text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            document.updateAsync { root, _ ->
                root.getAs<JsonArray>(DOCUMENT_EVENTS_KEY).putNewObject().apply {
                    set("date", date)
                    set("text", text.trim())
                }
            }.await()
        }
    }

    private fun updateEvent(date: String, text: String) {
        if (text.isBlank()) {
            deleteEvent(date)
            return
        }

        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val events = root.getAs<JsonArray>(DOCUMENT_EVENTS_KEY)
                val eventToUpdate = events.filterIsInstance<JsonObject>()
                    .find { it.getAs<JsonPrimitive>("date").getValueAs<String>() == date }
                eventToUpdate?.set("text", text.trim())
            }.await()
        }
    }

    private fun deleteEvent(date: String) {
        viewModelScope.launch {
            document.updateAsync { root, _ ->
                val events = root.getAs<JsonArray>(DOCUMENT_EVENTS_KEY)
                val eventToRemove = events.filterIsInstance<JsonObject>()
                    .find { it.getAs<JsonPrimitive>("date").getValueAs<String>() == date }
                eventToRemove?.let { events.remove(it.id) }
            }.await()
        }
    }

    private fun setSelectedDate(date: Date) {
        _state.value = _state.value.copy(selectedDate = date)
    }

    private fun setEventText(text: String) {
        _state.value = _state.value.copy(eventText = text)
    }

    private fun showEventDialog(show: Boolean) {
        _state.value = _state.value.copy(showEventDialog = show)
    }

    private fun navigateMonth(direction: MonthDirection) {
        val calendar = Calendar.getInstance()
        calendar.time = _state.value.displayMonth

        when (direction) {
            MonthDirection.PREVIOUS -> calendar.add(Calendar.MONTH, -1)
            MonthDirection.NEXT -> calendar.add(Calendar.MONTH, 1)
        }

        _state.value = _state.value.copy(displayMonth = calendar.time)
    }

    private fun updateEventsFromDocument(eventsArray: JsonArray) {
        viewModelScope.launch {
            val events = eventsArray.filterIsInstance<JsonObject>()
                .mapNotNull { eventObj ->
                    val date = eventObj.getAsOrNull<JsonPrimitive>("date")
                    val text = eventObj.getAsOrNull<JsonPrimitive>("text")

                    if (date != null && text != null) {
                        Event(
                            date = date.getValueAs(),
                            text = text.getValueAs(),
                        )
                    } else {
                        null
                    }
                }

            _state.value = _state.value.copy(
                events = events,
                isLoading = false,
                error = null,
            )
        }
    }

    override fun onCleared() {
        TerminationScope.launch {
            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
        super.onCleared()
    }

    companion object {
        private const val DOCUMENT_EVENTS_KEY = "content"
        private val TerminationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

        fun provideFactory(documentKey: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    documentKey ?: throw IllegalArgumentException("Document Key is not found")
                    val decodedDocumentKey = URLDecoder.decode(documentKey, UTF_8.name())
                    return SchedulerViewModel(decodedDocumentKey) as T
                }
            }
    }
}
