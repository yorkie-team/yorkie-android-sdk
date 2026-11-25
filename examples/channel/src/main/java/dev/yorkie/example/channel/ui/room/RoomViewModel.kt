/*
 * Copyright 2025 The Yorkie Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.yorkie.example.channel.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yorkie.core.Client
import dev.yorkie.example.channel.data.ChannelClientHolder
import dev.yorkie.example.channel.data.ChannelModel
import dev.yorkie.presence.Channel
import dev.yorkie.presence.ChannelEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JoinedStatus(
    val joined: Boolean = false,
    val count: Long = 0,
    val message: JoinedMessage? = null,
)

enum class JoinedMessage { Joined, Left }

data class RoomUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val numberOfMembers: Long = 0,
    val joinedStatus: JoinedStatus = JoinedStatus(),
    val isAnimating: Boolean = false,
)

class RoomViewModel(
    val room: ChannelModel,
    private val clientHolder: ChannelClientHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState())
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    /** The app's shared client. A room only attaches and detaches its channel on it. */
    private val client: Client get() = clientHolder.client

    private var channel: Channel? = null
    private var presenceJob: Job? = null
    private var animationJob: Job? = null

    /**
     * Set when the user leaves while the attach is still in flight, so the attach detaches the
     * channel it just took instead of leaving it attached to the shared client.
     */
    private var leaveRequested = false

    fun joinChannel() {
        if (channel != null || presenceJob != null) return
        leaveRequested = false
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val joining = Channel(room.channelKey)
                // Start collecting before the attach so no event is missed.
                presenceJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    joining.eventStream.collect { event ->
                        when (event) {
                            is ChannelEvent.Initialized -> onPresenceChanged(event.sessionCount)
                            is ChannelEvent.Changed -> onPresenceChanged(event.sessionCount)
                            else -> Unit
                        }
                    }
                }

                clientHolder.ensureActive()
                // The attach only registers the channel locally, so it is finished even when the
                // screen goes away meanwhile; a leave requested in the meantime detaches it.
                withContext(NonCancellable) {
                    client.attachChannel(joining, isRealtime = true).await().getOrThrow()

                    if (leaveRequested) {
                        presenceJob?.cancel()
                        presenceJob = null
                        client.detachChannel(joining).await().getOrThrow()
                    } else {
                        channel = joining
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                presenceJob?.cancel()
                presenceJob = null
                _uiState.update { it.copy(errorMessage = e.message ?: e.toString()) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Leaves the room: stops listening and detaches the channel. If the attach is still in
     * flight the join detaches the channel as soon as it completes.
     */
    suspend fun leaveChannel() {
        val leaving = channel
        if (leaving == null) {
            leaveRequested = true
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        try {
            presenceJob?.cancel()
            presenceJob = null
            client.detachChannel(leaving).await().getOrThrow()
            channel = null
            _uiState.update {
                it.copy(numberOfMembers = 0, joinedStatus = JoinedStatus())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = e.message ?: e.toString()) }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun onPresenceChanged(count: Long) {
        val previous = _uiState.value.numberOfMembers
        _uiState.update { state ->
            val status = when {
                count < previous -> JoinedStatus(false, count, JoinedMessage.Left)
                count > previous -> JoinedStatus(true, count, JoinedMessage.Joined)
                else -> state.joinedStatus
            }
            state.copy(
                numberOfMembers = count,
                joinedStatus = status,
                isAnimating = if (count != previous) true else state.isAnimating,
            )
        }
        if (count != previous) revertAnimating()
    }

    private fun revertAnimating() {
        animationJob?.cancel()
        animationJob = viewModelScope.launch {
            delay(ANIMATION_MILLIS)
            _uiState.update { it.copy(isAnimating = false) }
        }
    }

    override fun onCleared() {
        // Safety net for leaving without going through `leaveChannel` (e.g. the process-level
        // back gesture): the client outlives this ViewModel, so detach the channel here.
        channel?.let { client.detachChannel(it) }
        channel = null
        leaveRequested = true
        super.onCleared()
    }

    companion object {
        private const val ANIMATION_MILLIS = 300L

        fun provideFactory(
            room: ChannelModel,
            clientHolder: ChannelClientHolder,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RoomViewModel(room, clientHolder) }
        }
    }
}
