package com.watchparty.sync

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.watchparty.net.PlaybackSyncEvent
import com.watchparty.net.RoomState
import com.watchparty.net.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * SyncEngine — bridges SignalingClient's PlaybackSyncEvent stream to a live
 * ExoPlayer instance. Runs entirely on the Main dispatcher for ExoPlayer calls
 * (required by Media3) but is safe to drive from a foreground Service.
 *
 * Drift correction strategy (two-tier, see architecture notes):
 *   - |drift| < SOFT_THRESHOLD_MS               -> do nothing, within jitter tolerance
 *   - SOFT_THRESHOLD_MS <= |drift| < HARD_THRESHOLD_MS -> speed-nudge via PlaybackParameters
 *   - |drift| >= HARD_THRESHOLD_MS               -> hard seekTo() correction
 *
 * The host does NOT run drift correction against itself — it is the source of
 * truth and only emits PLAYBACK_PING. Guests run this engine; the host simply
 * ticks a ping loop (see startHostPingLoop).
 */
class SyncEngine(
    private val exoPlayer: ExoPlayer,
    private val signalingClient: SignalingClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate)
) {

    companion object {
        private const val SOFT_THRESHOLD_MS = 300L
        private const val HARD_THRESHOLD_MS = 2_500L
        private const val NUDGE_SPEED_UP = 1.05f
        private const val NUDGE_SPEED_DOWN = 0.95f
        private const val NORMAL_SPEED = 1.0f
        private const val HOST_PING_INTERVAL_MS = 2_000L
        private const val NUDGE_SETTLE_CHECK_MS = 500L
    }

    private val _driftMs = MutableStateFlow(0L)
    /** Current measured drift in ms (guest-only; 0 on host). Useful for a debug HUD. */
    val driftMs = _driftMs.asStateFlow()

    private val _isNudging = MutableStateFlow(false)
    val isNudging = _isNudging.asStateFlow()

    private var hostPingJob: Job? = null
    private var nudgeSettleJob: Job? = null
    private var listenerJob: Job? = null
    private var roleJob: Job? = null
    private var isHost = false

    /** Call once when entering the Watch Room. Safe to call whether host or guest. */
    fun start() {
        roleJob?.cancel()
        roleJob = scope.launch {
            signalingClient.roomState
                .map { it.isSelfHost }
                .distinctUntilChanged()
                .collect { promotedToHost ->
                    withContext(Dispatchers.Main.immediate) {
                        setHostMode(promotedToHost)
                    }
                }
        }

        listenerJob?.cancel()
        listenerJob = scope.launch {
            signalingClient.playbackSync.collectLatest { event ->
                withContext(Dispatchers.Main.immediate) {
                    when (event) {
                        is PlaybackSyncEvent.SourceChanged -> handleSourceChanged(event.sourceUrl)
                        is PlaybackSyncEvent.Snapshot -> if (!isHost) {
                            handleSnapshot(event.playback)
                        }
                        is PlaybackSyncEvent.Play -> if (!isHost) {
                            handleAuthoritativePlay(event.positionMs, event.serverTimeMs)
                        }
                        is PlaybackSyncEvent.Pause -> if (!isHost) {
                            handleAuthoritativePause(event.positionMs)
                        }
                        is PlaybackSyncEvent.Seek -> if (!isHost) {
                            handleAuthoritativeSeek(event.positionMs)
                        }
                        is PlaybackSyncEvent.Ping -> if (!isHost) {
                            handlePing(event.positionMs, event.serverTimeMs, event.state)
                        }
                        is PlaybackSyncEvent.AutoPause -> handleAutoPause()
                        PlaybackSyncEvent.BufferGateClear -> Unit // host UI decides whether to auto-resume
                    }
                }
            }
        }

        val initialPlayback = signalingClient.roomState.value.playback
        if (!isHost && !initialPlayback.sourceUrl.isNullOrBlank()) {
            handleSnapshot(initialPlayback)
        }
    }

    fun stop() {
        roleJob?.cancel()
        listenerJob?.cancel()
        hostPingJob?.cancel()
        nudgeSettleJob?.cancel()
        resetSpeed()
        isHost = false
    }

    // -- Host-side: authoritative control -------------------------------------------------

    /** Host calls these directly from UI controls. They both update ExoPlayer and broadcast. */
    fun hostPlay() {
        exoPlayer.play()
        signalingClient.sendPlay(exoPlayer.currentPosition)
        startHostPingLoop()
    }

    fun hostPause() {
        exoPlayer.pause()
        signalingClient.sendPause(exoPlayer.currentPosition)
        hostPingJob?.cancel()
    }

    fun hostSeek(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        signalingClient.sendSeek(positionMs)
    }

    /** Starts the periodic authoritative ping loop; call when host begins playback. */
    private fun startHostPingLoop() {
        hostPingJob?.cancel()
        hostPingJob = scope.launch {
            while (isActive && exoPlayer.isPlaying) {
                signalingClient.sendPlaybackPing(exoPlayer.currentPosition)
                delay(HOST_PING_INTERVAL_MS)
            }
        }
    }

    private fun setHostMode(enabled: Boolean) {
        if (isHost == enabled) return
        isHost = enabled
        if (enabled) {
            _driftMs.value = 0L
            resetSpeed()
            if (exoPlayer.isPlaying) startHostPingLoop()
        } else {
            hostPingJob?.cancel()
        }
    }

    // -- Guest-side: reactive correction -------------------------------------------------

    private fun handleSourceChanged(url: String) {
        exoPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        exoPlayer.prepare()
    }

    private fun handleSnapshot(playback: com.watchparty.net.PlaybackDto) {
        val url = playback.sourceUrl ?: return
        if (url.isBlank()) return

        val currentMediaItem = exoPlayer.currentMediaItem
        val currentUri = currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUri != url) {
            handleSourceChanged(url)
        }

        val target = if (playback.state == "playing") {
            extrapolate(playback.positionMs, playback.updatedAtMs)
        } else {
            playback.positionMs
        }

        hardSeekIfNeeded(target)
        if (playback.state == "playing") {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
        resetSpeed()
    }

    private fun handleAuthoritativePlay(positionMs: Long, serverTimeMs: Long) {
        val target = extrapolate(positionMs, serverTimeMs)
        hardSeekIfNeeded(target)
        exoPlayer.play()
    }

    private fun handleAuthoritativePause(positionMs: Long) {
        exoPlayer.pause()
        hardSeekIfNeeded(positionMs)
    }

    private fun handleAuthoritativeSeek(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        resetSpeed()
    }

    /** The core drift-correction decision, run on every host ping (~every 2s). */
    private fun handlePing(hostPositionMs: Long, serverTimeMs: Long, hostState: String) {
        if (hostState != "playing") return // pings only matter while playing
        if (!exoPlayer.isPlaying) return    // local isn't playing yet; PLAY event will handle join

        val expectedPositionMs = extrapolate(hostPositionMs, serverTimeMs)
        val localPositionMs = exoPlayer.currentPosition
        val drift = expectedPositionMs - localPositionMs
        _driftMs.value = drift

        when {
            abs(drift) < SOFT_THRESHOLD_MS -> {
                // Within tolerance — settle back to normal speed if we were nudging.
                if (_isNudging.value) resetSpeed()
            }
            abs(drift) < HARD_THRESHOLD_MS -> {
                nudgeTowards(drift)
            }
            else -> {
                hardSeekIfNeeded(expectedPositionMs)
            }
        }
    }

    private fun handleAutoPause() {
        exoPlayer.pause()
        resetSpeed()
    }

    // -- Correction primitives -------------------------------------------------

    /** Adjusts ExoPlayer's playback speed by a few percent to close the gap inaudibly. */
    private fun nudgeTowards(driftMs: Long) {
        val speed = if (driftMs > 0) NUDGE_SPEED_UP else NUDGE_SPEED_DOWN
        exoPlayer.playbackParameters = PlaybackParameters(speed)
        _isNudging.value = true

        // Re-check shortly after; if we've closed the gap, resetSpeed() will be called
        // on the next ping's soft-threshold branch. This job is a safety net so we never
        // get stuck at non-1.0x speed if pings stop arriving (e.g. host paused briefly).
        nudgeSettleJob?.cancel()
        nudgeSettleJob = scope.launch {
            delay(NUDGE_SETTLE_CHECK_MS * 6) // ~3s safety cap on any single nudge
            resetSpeed()
        }
    }

    private fun resetSpeed() {
        if (exoPlayer.playbackParameters.speed != NORMAL_SPEED) {
            exoPlayer.playbackParameters = PlaybackParameters(NORMAL_SPEED)
        }
        _isNudging.value = false
        nudgeSettleJob?.cancel()
    }

    /** Hard seek only — used for large corrections (host events, big drift, join). */
    private fun hardSeekIfNeeded(targetPositionMs: Long) {
        if (abs(exoPlayer.currentPosition - targetPositionMs) >= SOFT_THRESHOLD_MS) {
            exoPlayer.seekTo(targetPositionMs)
        }
        resetSpeed()
    }

    /** Accounts for network/relay latency between when the host sampled its position
     *  and "now" on this device, assuming playback continued at 1.0x on the host. */
    private fun extrapolate(hostPositionMs: Long, hostServerTimeMs: Long): Long {
        val elapsedSinceHostSample = System.currentTimeMillis() - hostServerTimeMs
        return hostPositionMs + elapsedSinceHostSample.coerceAtLeast(0)
    }

    // -- Buffering reporting (call from Player.Listener in the hosting screen) --------

    /** Wire this into a Player.Listener's onPlaybackStateChanged. */
    fun reportBufferState(isBuffering: Boolean) {
        signalingClient.sendBufferState(isBuffering)
    }
}

/**
 * Convenience Player.Listener wiring — attach to your ExoPlayer instance to
 * automatically report buffering state to the SyncEngine/server.
 *
 * Usage:
 *   exoPlayer.addListener(BufferReportingListener(syncEngine))
 */
class BufferReportingListener(private val syncEngine: SyncEngine) : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> syncEngine.reportBufferState(true)
            Player.STATE_READY -> syncEngine.reportBufferState(false)
        }
    }
}
