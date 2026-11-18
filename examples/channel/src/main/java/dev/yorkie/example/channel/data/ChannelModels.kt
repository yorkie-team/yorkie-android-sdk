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

data class ChannelCategory(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
)

data class ChannelModel(
    val id: String,
    val name: String,
    val description: String,
    val categoryId: String,
) {
    /** The channel key for a room, shared by the room list and the room screen. */
    val channelKey: String
        get() = "room-$id"
}

object ChannelRooms {
    private const val ROOMS_PER_CATEGORY = 4

    val categories: List<ChannelCategory> = listOf(
        ChannelCategory("general", "General", "💬", "General discussion"),
        ChannelCategory("development", "Development", "💻", "Tech talk and coding"),
        ChannelCategory("random", "Random", "🎲", "Off-topic chat"),
        ChannelCategory("music", "Music", "🎵", "Share your favorite tunes"),
    )

    /** Generates rooms with a hierarchical structure: each category yields a fixed number of rooms. */
    val rooms: List<ChannelModel> = categories.flatMap { category ->
        (1..ROOMS_PER_CATEGORY).map { index ->
            ChannelModel(
                id = "${category.id}.$index",
                name = "${category.emoji} ${category.name} #$index",
                description = category.description,
                categoryId = category.id,
            )
        }
    }

    fun roomsIn(category: ChannelCategory): List<ChannelModel> =
        rooms.filter { it.categoryId == category.id }

    fun find(roomId: String): ChannelModel? = rooms.firstOrNull { it.id == roomId }
}
