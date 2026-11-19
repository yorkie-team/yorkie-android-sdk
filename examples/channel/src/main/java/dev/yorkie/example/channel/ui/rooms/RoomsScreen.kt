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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.yorkie.example.channel.R
import dev.yorkie.example.channel.data.ChannelCategory
import dev.yorkie.example.channel.data.ChannelModel
import dev.yorkie.example.channel.data.ChannelRooms
import dev.yorkie.example.channel.ui.theme.GradientEnd
import dev.yorkie.example.channel.ui.theme.GradientStart
import dev.yorkie.example.channel.ui.theme.OnlineGreen
import kotlinx.coroutines.delay

private const val POLL_INTERVAL_MILLIS = 3_000L

@Composable
fun RoomsScreen(viewModel: RoomsViewModel, onJoinRoom: (ChannelModel) -> Unit) {
    val memberCounts by viewModel.memberCounts.collectAsState()

    // Poll each room's live member count without joining, using peekChannel.
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.refreshMemberCounts()
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(GradientStart, GradientEnd)))
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.rooms_title),
                style = MaterialTheme.typography.h5,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.rooms_subtitle),
                style = MaterialTheme.typography.subtitle1,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(ChannelRooms.categories, key = { it.id }) { category ->
                CategorySection(
                    category = category,
                    rooms = ChannelRooms.roomsIn(category),
                    memberCounts = memberCounts,
                    onJoinRoom = onJoinRoom,
                )
            }
        }
    }
}

@Composable
private fun CategorySection(
    category: ChannelCategory,
    rooms: List<ChannelModel>,
    memberCounts: Map<String, Long>,
    onJoinRoom: (ChannelModel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(
                text = "${category.emoji} ${category.name}",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = category.description,
                style = MaterialTheme.typography.body2,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        rooms.forEach { room ->
            RoomCard(
                room = room,
                memberCount = memberCounts[room.id] ?: 0L,
                onJoinRoom = { onJoinRoom(room) },
            )
        }
    }
}

@Composable
private fun RoomCard(
    room: ChannelModel,
    memberCount: Long,
    onJoinRoom: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = room.name,
                style = MaterialTheme.typography.h6,
                color = Color.Black,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "👥 $memberCount",
                style = MaterialTheme.typography.subtitle2,
                color = if (memberCount > 0) OnlineGreen else Color.Gray,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = room.description,
                style = MaterialTheme.typography.body1,
                color = Color.Black,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onJoinRoom) {
                Text(stringResource(R.string.join_room))
            }
        }
    }
}
