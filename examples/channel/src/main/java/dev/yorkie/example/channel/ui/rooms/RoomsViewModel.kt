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

package dev.yorkie.example.channel.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yorkie.example.channel.data.ChannelClientHolder
import dev.yorkie.example.channel.data.ChannelRooms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RoomsViewModel(private val clientHolder: ChannelClientHolder) : ViewModel() {

    private val _memberCounts = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Live member count per room id, populated via `peekChannel` without joining. */
    val memberCounts: StateFlow<Map<String, Long>> = _memberCounts.asStateFlow()

    /**
     * Refreshes every room's member count using `peekChannel`, which reads a channel's session
     * count without creating a session, heartbeating, or subscribing. The caller (the room list)
     * polls on its own cadence.
     */
    suspend fun refreshMemberCounts() {
        try {
            clientHolder.ensureActive()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            return
        }

        for (room in ChannelRooms.rooms) {
            // `peekChannel` requires a yorkie >= 0.7.9 server; older servers return
            // `unimplemented`. On failure, clear any stale count rather than leaving the
            // previous value showing.
            val count = clientHolder.client.peekChannel(room.channelKey).await().getOrNull()
            _memberCounts.update { counts ->
                if (count != null) counts + (room.id to count) else counts - room.id
            }
        }
    }

    companion object {
        fun provideFactory(clientHolder: ChannelClientHolder): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { RoomsViewModel(clientHolder) }
            }
    }
}
