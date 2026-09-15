package com.watchparty.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.watchparty.ui.home.HomeScreen
import com.watchparty.ui.lobby.LobbyScreen
import com.watchparty.ui.watchroom.WatchRoomScreen
import com.watchparty.viewmodel.RoomViewModel

private object Routes {
    const val HOME = "home"
    const val LOBBY = "lobby"
    const val WATCH_ROOM = "watch_room"
}

/**
 * [roomViewModel] is created ONCE at the Activity level (see MainActivity)
 * and threaded through here explicitly, rather than each screen calling
 * `viewModel()` itself — Compose Navigation's default viewModel() scopes to
 * the individual NavBackStackEntry, which would silently give Home, Lobby,
 * and WatchRoom three separate SignalingClient instances (three WebSocket
 * connections) instead of one shared session. Keep it this way, or migrate
 * to a service-bound singleton per the README "Known gaps" note.
 */
@Composable
fun AppNavHost(
    roomViewModel: RoomViewModel,
    cameraGranted: Boolean,
    micGranted: Boolean,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = roomViewModel,
                onRoomEntered = { navController.navigate(Routes.LOBBY) }
            )
        }
        composable(Routes.LOBBY) {
            LobbyScreen(
                viewModel = roomViewModel,
                cameraGranted = cameraGranted,
                micGranted = micGranted,
                onPartyStarted = { navController.navigate(Routes.WATCH_ROOM) },
                onLeave = {
                    roomViewModel.leaveRoom()
                    navController.popBackStack(Routes.HOME, inclusive = false)
                }
            )
        }
        composable(Routes.WATCH_ROOM) {
            WatchRoomScreen(
                viewModel = roomViewModel,
                cameraGranted = cameraGranted,
                micGranted = micGranted,
                onLeave = {
                    roomViewModel.leaveRoom()
                    navController.popBackStack(Routes.HOME, inclusive = false)
                }
            )
        }
    }
}
