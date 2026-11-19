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

package dev.yorkie.example.channel.data

import dev.yorkie.core.Client
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the app's shared [Client] and activates it once, however many callers ask at the same time.
 */
class ChannelClientHolder(val client: Client) {
    private val mutex = Mutex()

    /**
     * Activates the shared client unless it is already active. Concurrent callers wait for the
     * same activation instead of starting another one.
     */
    suspend fun ensureActive() {
        if (client.isActive) return
        mutex.withLock {
            if (client.isActive) return
            client.activateAsync().await().getOrThrow()
        }
    }
}
