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

package dev.yorkie.example.channel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.yorkie.example.channel.data.ChannelClientHolder
import dev.yorkie.example.channel.data.ChannelRooms
import dev.yorkie.example.channel.ui.room.RoomScreen
import dev.yorkie.example.channel.ui.room.RoomViewModel
import dev.yorkie.example.channel.ui.rooms.RoomsScreen
import dev.yorkie.example.channel.ui.rooms.RoomsViewModel
import kotlinx.coroutines.launch

private const val ROOMS_ROUTE = "rooms"
private const val ROOM_ID_ARG = "roomId"
private const val ROOM_ROUTE = "room/{$ROOM_ID_ARG}"

@Composable
fun ChannelAppHost(navController: NavHostController, clientHolder: ChannelClientHolder) {
    NavHost(navController = navController, startDestination = ROOMS_ROUTE) {
        composable(ROOMS_ROUTE) {
            val viewModel: RoomsViewModel = viewModel(
                factory = RoomsViewModel.provideFactory(clientHolder),
            )
            RoomsScreen(
                viewModel = viewModel,
                onJoinRoom = { room -> navController.navigate("room/${room.id}") },
            )
        }

        composable(
            route = ROOM_ROUTE,
            arguments = listOf(navArgument(ROOM_ID_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val room = backStackEntry.arguments?.getString(ROOM_ID_ARG)
                ?.let(ChannelRooms::find)
            if (room == null) {
                navController.popBackStack()
                return@composable
            }
            val viewModel: RoomViewModel = viewModel(
                factory = RoomViewModel.provideFactory(room, clientHolder),
            )
            val scope = rememberCoroutineScope()
            RoomScreen(
                viewModel = viewModel,
                onBack = {
                    scope.launch {
                        viewModel.leaveChannel()
                        navController.popBackStack()
                    }
                },
            )
        }
    }
}
