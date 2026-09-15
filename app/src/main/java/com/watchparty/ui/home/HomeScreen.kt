package com.watchparty.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchparty.WatchPartyApp
import com.watchparty.net.ConnectionState
import com.watchparty.viewmodel.RoomViewModel

@Composable
fun HomeScreen(viewModel: RoomViewModel, onRoomEntered: () -> Unit) {
    var displayName by remember { mutableStateOf("") }
    var roomCodeInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val connectionState by viewModel.connectionState.collectAsState()
    val roomState by viewModel.roomState.collectAsState()
    val currentServerUrl by viewModel.serverUrl.collectAsState()

    // Once a room code + client id land in RoomState, we've successfully
    // created or joined — move on to the Lobby.
    LaunchedEffect(roomState.roomCode, roomState.selfClientId) {
        if (roomState.roomCode != null && roomState.selfClientId != null) {
            onRoomEntered()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF08080C), Color(0xFF141016), Color(0xFF08080C))
                )
            )
    ) {
        // Top-right settings button
        IconButton(
            onClick = { showSettingsDialog = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Signaling Server Settings",
                tint = Color.White.copy(alpha = 0.7f)
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .fillMaxWidth(),
            color = Color(0xFF15151C).copy(alpha = 0.92f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Earl's WatchParty", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Private room. Shared movie. Live faces.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(14.dp))

                // Server status indicator pill
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF22222C),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier.clickable { showSettingsDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    when (connectionState) {
                                        is ConnectionState.Connected -> Color(0xFF4CAF50)
                                        is ConnectionState.Connecting -> Color(0xFFFFC107)
                                        is ConnectionState.Failed -> Color(0xFFF44336)
                                        ConnectionState.Disconnected -> Color.Gray
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = currentServerUrl.removePrefix("ws://").removePrefix("wss://"),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Your name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.createRoom(displayName.ifBlank { "Host" }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = displayName.isNotBlank() && connectionState !is ConnectionState.Connecting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        if (connectionState is ConnectionState.Connecting) "Connecting..." else "Create Room"
                    )
                }

                Spacer(Modifier.height(22.dp))
                Text("OR", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))

                OutlinedTextField(
                    value = roomCodeInput,
                    onValueChange = { roomCodeInput = it.uppercase().take(4) },
                    label = { Text("Room code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.joinRoom(roomCodeInput, displayName.ifBlank { "Guest" }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = displayName.isNotBlank() && roomCodeInput.length == 4 && connectionState !is ConnectionState.Connecting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30303A))
                ) {
                    Text(
                        if (connectionState is ConnectionState.Connecting) "Connecting..." else "Join Room"
                    )
                }

                if (connectionState is ConnectionState.Failed) {
                    val failureReason = (connectionState as ConnectionState.Failed).reason
                    val isRoomNotFound = failureReason.contains("ROOM_NOT_FOUND", ignoreCase = true)
                    val isRoomFull = failureReason.contains("ROOM_FULL", ignoreCase = true)
                    val isNetworkError = failureReason.contains("Failed to connect", ignoreCase = true) ||
                            failureReason.contains("UnknownHost", ignoreCase = true) ||
                            failureReason.contains("CLEARTEXT", ignoreCase = true) ||
                            failureReason.contains("ECONNREFUSED", ignoreCase = true) ||
                            failureReason.contains("connection_failed", ignoreCase = true)

                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = Color(0xFF3B1F24),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = when {
                                    isRoomNotFound -> "Room not found. Check the code and try again."
                                    isRoomFull -> "This room is full (max 4 participants)."
                                    isNetworkError -> "Cannot reach signaling server. Verify server is running and check settings (⚙)."
                                    else -> "Connection failed: $failureReason"
                                },
                                color = Color(0xFFFFB4AB),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (isNetworkError) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Target: $currentServerUrl",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFFB4AB).copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        var tempUrl by remember(currentServerUrl) { mutableStateOf(currentServerUrl) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Signaling Server Settings", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Select a preset or enter the address of your Node.js signaling server (server.js).",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { tempUrl = WatchPartyApp.DEFAULT_EMULATOR_URL },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Emulator\n10.0.2.2", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = { tempUrl = WatchPartyApp.DEFAULT_LAN_URL },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("LAN / Wi-Fi\n192.168.x", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("ws://10.0.2.2:8080") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setServerUrl(tempUrl)
                        showSettingsDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
