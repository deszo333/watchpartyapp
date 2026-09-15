package com.watchparty.ui.watchroom

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.media.AudioManager
import android.util.TypedValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.isActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.watchparty.net.PlaybackSyncEvent
import com.watchparty.viewmodel.RoomViewModel
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import android.graphics.Color as AndroidColor

@Composable
fun WatchRoomScreen(
    viewModel: RoomViewModel,
    cameraGranted: Boolean,
    micGranted: Boolean,
    onLeave: () -> Unit
) {
    LaunchedEffect(cameraGranted, micGranted) {
        viewModel.initWatchRoom(cameraEnabled = cameraGranted, micEnabled = micGranted)
    }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.requestedOrientation = originalOrientation
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val roomState by viewModel.roomState.collectAsState()
    val remoteVideoTracks by viewModel.remoteVideoTracks.collectAsState()
    val subtitleTracks by viewModel.subtitleTracks.collectAsState()
    val selectedSubtitleTrackId by viewModel.selectedSubtitleTrackId.collectAsState()
    val subtitlesEnabled by viewModel.subtitlesEnabled.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var chatVisible by remember { mutableStateOf(false) }
    var bufferGateMessage by remember { mutableStateOf<String?>(null) }
    var hostResumePrompt by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var subtitleMenuExpanded by remember { mutableStateOf(false) }
    var subtitleStyle by remember { mutableStateOf(SubtitleStyle.Cinema) }
    val chatLog = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    var currentPositionMs by remember { mutableStateOf(0L) }
    var totalDurationMs by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf(0L) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var sourceUrlInput by remember { mutableStateOf("") }
    var showHostPromotionNotice by remember { mutableStateOf(false) }
    var lastInteractionTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    var skipFeedback by remember { mutableStateOf<String?>(null) }
    var gestureHudMessage by remember { mutableStateOf<String?>(null) }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    LaunchedEffect(skipFeedback) {
        if (skipFeedback != null) {
            delay(800)
            skipFeedback = null
        }
    }

    LaunchedEffect(gestureHudMessage) {
        if (gestureHudMessage != null) {
            delay(1200)
            gestureHudMessage = null
        }
    }

    fun pingControls() {
        controlsVisible = true
        lastInteractionTimestamp = System.currentTimeMillis()
    }

    // Auto-hide controls after 3s of inactivity, paused during chat/menus/scrubbing/dialogs
    LaunchedEffect(controlsVisible, lastInteractionTimestamp, chatVisible, subtitleMenuExpanded, isScrubbing, showUrlDialog) {
        if (controlsVisible && !chatVisible && !subtitleMenuExpanded && !isScrubbing && !showUrlDialog) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Continuously update position and duration
    LaunchedEffect(viewModel.exoPlayer) {
        while (isActive) {
            val player = viewModel.exoPlayer
            if (player != null && !isScrubbing) {
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                totalDurationMs = player.duration.coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    var hadHostRole by remember { mutableStateOf(roomState.isSelfHost) }
    LaunchedEffect(roomState.isSelfHost) {
        if (roomState.isSelfHost && !hadHostRole) {
            hadHostRole = true
            showHostPromotionNotice = true
            delay(4000)
            showHostPromotionNotice = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.chatEvents.collect { evt ->
            chatLog.add("${evt.fromName}: ${evt.text}")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.bufferGateEvents.collect { evt ->
            when (evt) {
                is PlaybackSyncEvent.AutoPause -> {
                    val bufferingNames = evt.bufferingClientIds.mapNotNull { id ->
                        roomState.participants.firstOrNull { it.id == id }?.name
                    }
                    bufferGateMessage = if (bufferingNames.isNotEmpty()) {
                        "Paused — ${bufferingNames.joinToString(", ")} is buffering…"
                    } else {
                        "Paused — someone's buffering…"
                    }
                    hostResumePrompt = false
                }
                PlaybackSyncEvent.BufferGateClear -> {
                    bufferGateMessage = null
                    if (roomState.isSelfHost) {
                        hostResumePrompt = true
                    }
                }
                else -> Unit
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // -- Video surface (base layer) --------------------------------------------------
        val player = viewModel.exoPlayer
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false // custom controls drawn in Compose below
                        this.player = player
                        applySubtitleStyle(subtitleStyle)
                    }
                },
                update = { playerView ->
                    playerView.player = player
                    playerView.applySubtitleStyle(subtitleStyle)
                },
                modifier = Modifier.fillMaxSize()
            )

            // -- Netflix-style Center & Screen Tap Surface --------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(roomState.isSelfHost) {
                        detectTapGestures(
                            onTap = {
                                pingControls()
                                controlsVisible = !controlsVisible
                            },
                            onDoubleTap = { offset ->
                                if (roomState.isSelfHost) {
                                    val width = size.width
                                    val cur = viewModel.exoPlayer?.currentPosition ?: 0L
                                    val dur = viewModel.exoPlayer?.duration ?: 0L
                                    if (offset.x < width * 0.35f) {
                                        val newPos = (cur - 10_000L).coerceAtLeast(0L)
                                        viewModel.hostSeek(newPos)
                                        skipFeedback = "⏪ 10"
                                        pingControls()
                                    } else if (offset.x > width * 0.65f) {
                                        val newPos = if (dur > 0L) (cur + 10_000L).coerceAtMost(dur) else cur + 10_000L
                                        viewModel.hostSeek(newPos)
                                        skipFeedback = "10 ⏩"
                                        pingControls()
                                    } else {
                                        viewModel.hostPlayPause()
                                        pingControls()
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val width = activity?.window?.decorView?.width ?: 1920
                            val touchX = change.position.x
                            if (touchX < width * 0.5f) {
                                // Left side: Brightness
                                val window = activity?.window
                                val currentBrightness = window?.attributes?.screenBrightness?.let {
                                    if (it < 0f) 0.5f else it
                                } ?: 0.5f
                                val delta = -dragAmount / 400f
                                val newBrightness = (currentBrightness + delta).coerceIn(0.05f, 1f)
                                window?.attributes = window?.attributes?.apply {
                                    screenBrightness = newBrightness
                                }
                                gestureHudMessage = "Brightness ${(newBrightness * 100).toInt()}%"
                            } else {
                                // Right side: Volume
                                if (audioManager != null) {
                                    val direction = if (dragAmount < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    gestureHudMessage = "Volume ${(currentVol * 100 / maxVol)}%"
                                }
                            }
                        }
                    }
            )
        }

        // -- Center Skip / HUD pill -------------------------------------------
        val activePill = skipFeedback ?: gestureHudMessage
        if (activePill != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Text(
                    text = activePill,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }

        // -- Buffering / auto-pause overlay --------------------------------------------------
        bufferGateMessage?.let { message ->
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text(message, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }

        // -- Floating camera tiles --------------------------------------------------
        val sharedEglBase = viewModel.eglBase
        if (sharedEglBase != null && remoteVideoTracks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (controlsVisible) 56.dp else 12.dp, end = 12.dp)
                    .widthIn(max = 376.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                remoteVideoTracks.entries.take(3).forEach { (peerId, track) ->
                    RemoteCameraTile(
                        peerId = peerId,
                        track = track,
                        eglBase = sharedEglBase
                    )
                }
            }
        }

        // -- Netflix-Style Full Controls Overlay --------------------------------------------------
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // TOP BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onLeave) {
                            Text("←", color = Color.White, fontSize = 24.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Earl's WatchParty",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Room ${roomState.roomCode ?: ""} • ${if (roomState.isSelfHost) "Host" else "Guest"}",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (roomState.isSelfHost) {
                            TextButton(onClick = { showUrlDialog = true; pingControls() }) {
                                Text("Load Source", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { chatVisible = !chatVisible; pingControls() }) {
                            Text("Chat", color = Color.White)
                        }
                    }
                }

                // CENTER PLAY / PAUSE / SEEK BUTTONS (Netflix style)
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(40.dp)
                ) {
                    // Rewind 10s
                    IconButton(
                        onClick = {
                            if (roomState.isSelfHost) {
                                val cur = viewModel.exoPlayer?.currentPosition ?: 0L
                                viewModel.hostSeek((cur - 10_000L).coerceAtLeast(0L))
                                skipFeedback = "⏪ 10"
                                pingControls()
                            }
                        },
                        enabled = roomState.isSelfHost,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("⏪ 10", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }

                    // Main Big Play/Pause Button
                    IconButton(
                        onClick = {
                            if (roomState.isSelfHost) {
                                viewModel.hostPlayPause()
                                pingControls()
                            }
                        },
                        enabled = roomState.isSelfHost,
                        modifier = Modifier.size(76.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val playing = viewModel.exoPlayer?.isPlaying == true
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }

                    // Forward 10s
                    IconButton(
                        onClick = {
                            if (roomState.isSelfHost) {
                                val cur = viewModel.exoPlayer?.currentPosition ?: 0L
                                val dur = viewModel.exoPlayer?.duration ?: 0L
                                val newPos = if (dur > 0L) (cur + 10_000L).coerceAtMost(dur) else cur + 10_000L
                                viewModel.hostSeek(newPos)
                                skipFeedback = "10 ⏩"
                                pingControls()
                            }
                        },
                        enabled = roomState.isSelfHost,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("10 ⏩", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // BOTTOM CONTROLS & TIMELINE
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    val displayPos = if (isScrubbing) scrubPositionMs else currentPositionMs
                    val displayDur = totalDurationMs
                    val sliderProgress = if (displayDur > 0L) {
                        (displayPos.toFloat() / displayDur.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    // Netflix Timeline with red active track
                    Slider(
                        value = sliderProgress,
                        onValueChange = { frac ->
                            if (roomState.isSelfHost && displayDur > 0L) {
                                isScrubbing = true
                                scrubPositionMs = (frac * displayDur).toLong()
                                pingControls()
                            }
                        },
                        onValueChangeFinished = {
                            if (roomState.isSelfHost && displayDur > 0L) {
                                viewModel.hostSeek(scrubPositionMs)
                                currentPositionMs = scrubPositionMs
                                isScrubbing = false
                                pingControls()
                            }
                        },
                        enabled = roomState.isSelfHost && displayDur > 0L,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFE50914), // Netflix red
                            activeTrackColor = Color(0xFFE50914),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current time & total duration
                        Text(
                            text = "${formatTime(displayPos)} / ${if (displayDur > 0L) formatTime(displayDur) else "--:--"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )

                        // Subtitle & Audio options
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (subtitleTracks.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { subtitleMenuExpanded = true }
                                ) {
                                    Text(
                                        text = "Subtitles: ${subtitleTracks.firstOrNull { it.id == selectedSubtitleTrackId }?.label ?: "Off"}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.sp
                                    )
                                }

                                DropdownMenu(
                                    expanded = subtitleMenuExpanded,
                                    onDismissRequest = { subtitleMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Off") },
                                        onClick = {
                                            viewModel.setSubtitlesEnabled(false)
                                            subtitleMenuExpanded = false
                                        }
                                    )
                                    subtitleTracks.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                viewModel.setSubtitlesEnabled(true)
                                                viewModel.selectSubtitleTrack(option.id)
                                                subtitleMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -- Chat drawer --------------------------------------------------
        AnimatedVisibility(
            visible = chatVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.75f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0B10).copy(alpha = 0.92f))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    chatLog.takeLast(50).forEach { line ->
                        Text(line, color = Color.White.copy(alpha = 0.9f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (chatInput.isNotBlank()) {
                            viewModel.sendChat(chatInput)
                            chatInput = ""
                            pingControls()
                        }
                    }) { Text("Send", color = Color.White) }
                }
            }
        }

        // -- Host resume banner when buffer gate clears ------------------------
        if (hostResumePrompt && roomState.isSelfHost) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                color = Color(0xFF1E281E),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("All participants ready", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            viewModel.hostPlayPause()
                            hostResumePrompt = false
                            pingControls()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text("Resume")
                    }
                }
            }
        }

        // -- Host migration notice ---------------------------------------------
        if (showHostPromotionNotice) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
                color = Color(0xFF2C2210),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.6f))
            ) {
                Text(
                    text = "You are now the room host",
                    color = Color(0xFFFFE082),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // -- Empty state prompt -----------------------------------------------
        if (roomState.playback.sourceUrl.isNullOrBlank() && roomState.isSelfHost) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { showUrlDialog = true; pingControls() },
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("Set Video Stream URL")
                }
            }
        }

        // -- Set Video URL Dialog ---------------------------------------------
        if (showUrlDialog && roomState.isSelfHost) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("Movie Stream URL") },
                text = {
                    Column {
                        Text(
                            "Enter direct HTTPS MP4 stream URL:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = sourceUrlInput,
                            onValueChange = { sourceUrlInput = it },
                            placeholder = { Text("https://example.com/movie.mp4") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (sourceUrlInput.isNotBlank()) {
                                viewModel.hostSetSource(sourceUrlInput.trim())
                                showUrlDialog = false
                                pingControls()
                            }
                        }
                    ) { Text("Load & Sync") }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) {
        "%d:%02d:%02d".format(hr, min, sec)
    } else {
        "%02d:%02d".format(min, sec)
    }
}

private enum class SubtitleStyle(
    val label: String,
    val captionStyle: CaptionStyleCompat,
    val textSizeSp: Float
) {
    Cinema(
        label = "Cinema",
        captionStyle = CaptionStyleCompat(
            AndroidColor.WHITE,
            AndroidColor.TRANSPARENT,
            AndroidColor.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            AndroidColor.BLACK,
            Typeface.DEFAULT_BOLD
        ),
        textSizeSp = 18f
    ),
    HighContrast(
        label = "High contrast",
        captionStyle = CaptionStyleCompat(
            AndroidColor.WHITE,
            AndroidColor.BLACK,
            AndroidColor.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            AndroidColor.BLACK,
            Typeface.DEFAULT_BOLD
        ),
        textSizeSp = 19f
    );

    fun next(): SubtitleStyle = when (this) {
        Cinema -> HighContrast
        HighContrast -> Cinema
    }
}

private fun PlayerView.applySubtitleStyle(style: SubtitleStyle) {
    subtitleView?.setStyle(style.captionStyle)
    subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
}

@Composable
private fun RemoteCameraTile(peerId: String, track: VideoTrack, eglBase: EglBase) {
    val rendererRef = remember(peerId, track) {
        mutableStateOf<SurfaceViewRenderer?>(null)
    }

    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBase.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setMirror(false)
                setZOrderMediaOverlay(true)
                track.addSink(this)
                rendererRef.value = this
            }
        },
        modifier = Modifier
            .size(width = 112.dp, height = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
    )

    DisposableEffect(peerId, track) {
        onDispose {
            rendererRef.value?.let { renderer ->
                track.removeSink(renderer)
                renderer.release()
            }
            rendererRef.value = null
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
