package com.watchparty.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.watchparty.WatchPartyApp
import com.watchparty.net.ChatEvent
import com.watchparty.net.PlaybackSyncEvent
import com.watchparty.net.SignalingClient
import com.watchparty.rtc.WebRtcMeshManager
import com.watchparty.sync.BufferReportingListener
import com.watchparty.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase

/**
 * Owns the long-lived playback/session graph so a configuration change or a
 * brief backgrounding does not recreate the WebSocket, ExoPlayer, SyncEngine,
 * or WebRTC mesh. RoomViewModel binds to this service and proxies UI calls.
 */
class PlaybackForegroundService : MediaSessionService() {

    data class SubtitleTrackOption(
        val id: String,
        val label: String
    )

    inner class LocalBinder : Binder() {
        fun service(): PlaybackForegroundService = this@PlaybackForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    lateinit var signalingClient: SignalingClient
        private set

    lateinit var syncEngine: SyncEngine
        private set

    var meshManager: WebRtcMeshManager? = null
        private set

    val localVideoTrack: org.webrtc.VideoTrack?
        get() = meshManager?.localVideoTrack

    val eglBase: EglBase = EglBase.create()

    private val _bufferGateEvents =
        MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 4)
    val bufferGateEvents = _bufferGateEvents.asSharedFlow()

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrackOption>>(emptyList())
    val subtitleTracks = _subtitleTracks.asStateFlow()

    private val _selectedSubtitleTrackId = MutableStateFlow<String?>(null)
    val selectedSubtitleTrackId = _selectedSubtitleTrackId.asStateFlow()

    private val _subtitlesEnabled = MutableStateFlow(true)
    val subtitlesEnabled = _subtitlesEnabled.asStateFlow()

    private val subtitleTrackRefs = mutableMapOf<String, Pair<androidx.media3.common.TrackGroup, Int>>()

    private val subtitleTrackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            refreshSubtitleTracks(tracks)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build()
        signalingClient = SignalingClient(
            serverUrl = WatchPartyApp.serverUrl,
            externalScope = serviceScope
        )
        syncEngine = SyncEngine(player, signalingClient, serviceScope).also { engine ->
            engine.start()
            player.addListener(BufferReportingListener(engine))
        }
        player.addListener(subtitleTrackListener)
        mediaSession = MediaSession.Builder(this, player).build()

        serviceScope.launch {
            signalingClient.playbackSync.collect { event ->
                if (event is PlaybackSyncEvent.AutoPause || event is PlaybackSyncEvent.BufferGateClear) {
                    _bufferGateEvents.tryEmit(event)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    fun updateServerUrl(url: String) {
        signalingClient.updateServerUrl(url)
    }

    fun createRoom(displayName: String) = signalingClient.createRoom(displayName)

    fun joinRoom(roomCode: String, displayName: String) = signalingClient.joinRoom(roomCode, displayName)

    fun setReady(ready: Boolean) = signalingClient.setReady(ready)

    fun ensureWatchRoomStarted(cameraEnabled: Boolean = true, micEnabled: Boolean = true) {
        if (meshManager != null) {
            meshManager?.setCameraEnabled(cameraEnabled)
            meshManager?.setMicEnabled(micEnabled)
            return
        }

        meshManager = WebRtcMeshManager(
            appContext = applicationContext,
            signalingClient = signalingClient,
            eglBase = eglBase,
            ownsEglBase = false,
            scope = serviceScope
        ).also { mesh ->
            mesh.init(enableCamera = cameraEnabled, enableMic = micEnabled)
            mesh.start()
        }
    }

    fun hostSetSource(url: String) = signalingClient.setPlaybackSource(url)

    fun hostPlayPause() {
        if (player.isPlaying) syncEngine.hostPause() else syncEngine.hostPlay()
    }

    fun hostSeek(positionMs: Long) = syncEngine.hostSeek(positionMs)

    fun sendChat(text: String) = signalingClient.sendChat(text)

    fun chatEvents(): kotlinx.coroutines.flow.SharedFlow<ChatEvent> = signalingClient.chatEvents

    fun setSubtitlesEnabled(enabled: Boolean) {
        _subtitlesEnabled.value = enabled
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    fun selectSubtitleTrack(trackId: String?) {
        _selectedSubtitleTrackId.value = trackId
        val builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, trackId == null || !_subtitlesEnabled.value)

        val ref = trackId?.let { subtitleTrackRefs[it] }
        if (ref != null) {
            val (group, trackIndex) = ref
            builder.addOverride(TrackSelectionOverride(group, trackIndex))
        }

        player.trackSelectionParameters = builder.build()
    }

    fun leaveRoom() {
        syncEngine.stop()
        meshManager?.dispose()
        meshManager = null
        player.stop()
        player.clearMediaItems()
        signalingClient.disconnect()
        subtitleTrackRefs.clear()
        _subtitleTracks.value = emptyList()
        _selectedSubtitleTrackId.value = null
    }

    private fun refreshSubtitleTracks(tracks: Tracks) {
        subtitleTrackRefs.clear()
        val options = buildList {
            tracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .forEachIndexed { groupIndex, group ->
                    for (trackIndex in 0 until group.length) {
                        if (!group.isTrackSupported(trackIndex)) continue
                        val format = group.getTrackFormat(trackIndex)
                        val id = "${groupIndex}_${trackIndex}_${format.id ?: format.language ?: "text"}"
                        val label = listOfNotNull(
                            format.label,
                            format.language?.uppercase(),
                            format.sampleMimeType?.substringAfterLast(".")
                        ).firstOrNull { it.isNotBlank() } ?: "Subtitle ${size + 1}"
                        subtitleTrackRefs[id] = group.mediaTrackGroup to trackIndex
                        add(SubtitleTrackOption(id = id, label = label))
                    }
                }
        }
        _subtitleTracks.value = options
        if (_selectedSubtitleTrackId.value !in options.map { it.id }) {
            _selectedSubtitleTrackId.value = options.firstOrNull()?.id
        }
    }

    override fun onDestroy() {
        leaveRoom()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        player.removeListener(subtitleTrackListener)
        player.release()
        eglBase.release()
        super.onDestroy()
    }

    /** Stop the service (and drop the foreground notification) once idle+paused. */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }
}
