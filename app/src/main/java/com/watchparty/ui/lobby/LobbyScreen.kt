package com.watchparty.ui.lobby

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.watchparty.viewmodel.RoomViewModel
import org.webrtc.SurfaceViewRenderer

@Composable
fun LobbyScreen(
    viewModel: RoomViewModel,
    cameraGranted: Boolean,
    micGranted: Boolean,
    onPartyStarted: () -> Unit,
    onLeave: () -> Unit
) {
    val roomState by viewModel.roomState.collectAsState()
    var isReady by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var cameraEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(cameraGranted) }
    var micEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(micGranted) }

    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 4f, targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar1"
    )
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 8f, targetValue = 24f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar2"
    )
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 20f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar3"
    )
    val bar4Height by infiniteTransition.animateFloat(
        initialValue = 4f, targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar4"
    )

    androidx.compose.runtime.LaunchedEffect(cameraEnabled, micEnabled) {
        viewModel.initLobbyMedia(cameraEnabled = cameraEnabled, micEnabled = micEnabled)
    }

    androidx.compose.runtime.LaunchedEffect(roomState.playback.sourceUrl, roomState.playback.state) {
        if (!roomState.isSelfHost && (!roomState.playback.sourceUrl.isNullOrBlank() || roomState.playback.state == "playing")) {
            onPartyStarted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF08080C), Color(0xFF111118))))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Room ${roomState.roomCode ?: "----"}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (roomState.isSelfHost) "Host" else "Guest",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                OutlinedButton(onClick = onLeave) { Text("Leave") }
            }

            Spacer(Modifier.height(24.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(165.dp),
                color = Color(0xFF17171F),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val localTrack = viewModel.localVideoTrack
                    val eglBase = viewModel.eglBase
                    val isCameraActive = cameraGranted && cameraEnabled && localTrack != null && eglBase != null
                    if (isCameraActive) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).apply {
                                    init(eglBase!!.eglBaseContext, null)
                                    setEnableHardwareScaler(true)
                                    setMirror(true)
                                    localTrack!!.addSink(this)
                                }
                            },
                            modifier = Modifier
                                .size(width = 130.dp, height = 140.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 130.dp, height = 140.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (!cameraGranted || !cameraEnabled) "Camera Off" else "Starting…",
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Device Check", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            // Animated visualizer
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.height(18.dp)
                            ) {
                                val bars = listOf(bar1Height, bar2Height, bar3Height, bar4Height)
                                bars.forEach { h ->
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(if (micEnabled && micGranted) h.dp else 3.dp)
                                            .background(
                                                if (micEnabled && micGranted) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.4f),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                        }

                        // Camera toggle row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (cameraEnabled && cameraGranted) "Camera live" else "Camera off",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cameraEnabled && cameraGranted) Color(0xFFB7F7C1) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = cameraEnabled && cameraGranted,
                                onCheckedChange = { cameraEnabled = it },
                                enabled = cameraGranted
                            )
                        }

                        // Mic toggle row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (micEnabled && micGranted) "Mic live" else "Mic muted",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (micEnabled && micGranted) Color(0xFFB7F7C1) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = micEnabled && micGranted,
                                onCheckedChange = { micEnabled = it },
                                enabled = micGranted
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Text(
                "Who's here",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(roomState.participants) { participant ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        color = Color(0xFF17171F),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(participant.name, color = Color.White)
                                if (participant.isHost) {
                                    Text(
                                        "Host",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                            Text(
                                if (participant.ready) "Ready" else "Not ready",
                                color = if (participant.ready) {
                                    Color(0xFFB7F7C1)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                color = Color(0xFF15151C),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("I'm ready")
                    Switch(
                        checked = isReady,
                        onCheckedChange = {
                            isReady = it
                            viewModel.setReady(it)
                        }
                    )
                }
            }

            if (roomState.isSelfHost) {
                Button(
                    onClick = onPartyStarted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Start Party") }
            } else {
                Text(
                    "Waiting for host to start",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
