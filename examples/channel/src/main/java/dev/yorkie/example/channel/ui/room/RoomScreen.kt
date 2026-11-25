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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yorkie.example.channel.R
import dev.yorkie.example.channel.ui.theme.LeftYellow
import dev.yorkie.example.channel.ui.theme.OnlineGreen
import java.util.Locale

@Composable
fun RoomScreen(viewModel: RoomViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.joinChannel()
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back_to_rooms))
            }
        }

        Text(text = viewModel.room.name, style = MaterialTheme.typography.h4)
        Text(text = viewModel.room.description, style = MaterialTheme.typography.h6)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator()
            }
            if (!state.isLoading && state.errorMessage == null) {
                UserOnline(state)
            }
            state.errorMessage?.let {
                Text(text = it, style = MaterialTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun UserOnline(state: RoomUiState) {
    val scale by animateFloatAsState(
        targetValue = if (state.isAnimating) 1.5f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "memberCountScale",
    )
    val label = stringResource(
        if (state.numberOfMembers > 1) R.string.users_online else R.string.user_online,
    ).uppercase(Locale.getDefault())

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${state.numberOfMembers}",
            style = MaterialTheme.typography.h5,
            modifier = Modifier.scale(scale),
        )
        Text(text = label, modifier = Modifier.scale(scale))

        state.joinedStatus.message?.let { message ->
            Text(
                text = stringResource(
                    if (message == JoinedMessage.Joined) {
                        R.string.someone_joined
                    } else {
                        R.string.someone_left
                    },
                ),
                modifier = Modifier
                    .padding(16.dp)
                    .background(
                        color = if (state.joinedStatus.joined) OnlineGreen else LeftYellow,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(16.dp),
            )
        }
    }
}
