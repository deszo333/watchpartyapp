package com.watchparty.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import com.watchparty.WatchPartyApp
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.watchparty.net.ChatEvent
import com.watchparty.net.ConnectionState
import com.watchparty.net.PlaybackSyncEvent
import com.watchparty.net.RoomState
import com.watchparty.rtc.WebRtcMeshManager
import com.watchparty.service.PlaybackForegroundService
import com.watchparty.service.PlaybackForegroundService.SubtitleTrackOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Activity-scoped UI facade for the foreground playback service. The service
 * owns the session objects; this ViewModel binds to it and mirrors state into
 * Compose-friendly flows while proxying user actions.
 */
class RoomViewModel(application: Application) : AndroidViewModel(application) {

    private var service: PlaybackForegroundService? = null
    private var bound = false
    private val pendingActions = mutableListOf<(PlaybackForegroundService) -> Unit>()
    private val mirrorJobs = mutableListOf<Job>()
    private var meshMirrorJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverUrl = MutableStateFlow(WatchPartyApp.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _roomState = MutableStateFlow(RoomState())
    val roomState: StateFlow<RoomState> = _roomState.asStateFlow()

    private val _bufferGateEvents =
        MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 4)
    val bufferGateEvents: SharedFlow<PlaybackSyncEvent> = _bufferGateEvents.asSharedFlow()

    private val _chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 32)
    val chatEvents: SharedFlow<ChatEvent> = _chatEvents.asSharedFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrackOption>> = _subtitleTracks.asStateFlow()

    private val _selectedSubtitleTrackId = MutableStateFlow<String?>(null)
    val selectedSubtitleTrackId: StateFlow<String?> = _selectedSubtitleTrackId.asStateFlow()

    private val _subtitlesEnabled = MutableStateFlow(true)
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    val exoPlayer: ExoPlayer?
        get() = service?.player

    val meshManager: WebRtcMeshManager?
        get() = service?.meshManager

    val eglBase: EglBase?
        get() = service?.eglBase

    val localVideoTrack: VideoTrack?
        get() = service?.localVideoTrack

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val playbackService = (binder as PlaybackForegroundService.LocalBinder).service()
            service = playbackService
            bound = true
            mirrorServiceState(playbackService)

            val queued = pendingActions.toList()
            pendingActions.clear()
            queued.forEach { it(playbackService) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearMirrors()
            service = null
            bound = false
            _connectionState.value = ConnectionState.Disconnected
            _roomState.value = RoomState()
        }
    }

    init {
        bindPlaybackService()
    }

    private fun bindPlaybackService() {
        if (bound) return
        val intent = Intent(getApplication(), PlaybackForegroundService::class.java)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun withService(action: (PlaybackForegroundService) -> Unit) {
        service?.let(action) ?: run {
            pendingActions += action
            bindPlaybackService()
        }
    }

    private fun mirrorServiceState(playbackService: PlaybackForegroundService) {
        clearMirrors()
        mirrorJobs += viewModelScope.launch {
            playbackService.signalingClient.connectionState.collect { _connectionState.value = it }
        }
        mirrorJobs += viewModelScope.launch {
            playbackService.signalingClient.roomState.collect { _roomState.value = it }
        }
        mirrorJobs += viewModelScope.launch {
            playbackService.bufferGateEvents.collect { _bufferGateEvents.emit(it) }
        }
        mirrorJobs += viewModelScope.launch {
            playbackService.chatEvents().collect { _chatEvents.emit(it) }
        }
        mirrorJobs += viewModelScope.launch {
            playbackService.subtitleTracks.collect { _subtitleTracks.value = it }
        }
        mirrorJobs += viewModelScope.launch {
            playbackService.selectedSubtitleTrackId.collect { _selectedSubtitleTrackId.value = it }
        }
        mirrorJobs += viewModelScope.launch {
            playbackService.subtitlesEnabled.collect { _subtitlesEnabled.value = it }
        }
    }

    private fun clearMirrors() {
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs.clear()
        meshMirrorJob?.cancel()
        meshMirrorJob = null
    }

    fun setServerUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        WatchPartyApp.serverUrl = trimmed
        _serverUrl.value = trimmed
        withService { it.updateServerUrl(trimmed) }
    }

    fun createRoom(displayName: String) = withService { it.createRoom(displayName) }

    fun joinRoom(roomCode: String, displayName: String) = withService {
        it.joinRoom(roomCode, displayName)
    }

    fun setReady(ready: Boolean) = withService { it.setReady(ready) }

    fun initLobbyMedia(cameraEnabled: Boolean = true, micEnabled: Boolean = true) = withService {
        it.ensureWatchRoomStarted(cameraEnabled, micEnabled)
        mirrorMeshState(it)
    }

    fun initWatchRoom(cameraEnabled: Boolean = true, micEnabled: Boolean = true) = withService {
        it.ensureWatchRoomStarted(cameraEnabled, micEnabled)
        mirrorMeshState(it)
    }

    fun hostSetSource(url: String) = withService { it.hostSetSource(url) }

    fun hostPlayPause() = withService { it.hostPlayPause() }

    fun hostSeek(positionMs: Long) = withService { it.hostSeek(positionMs) }

    fun sendChat(text: String) = withService { it.sendChat(text) }

    fun setSubtitlesEnabled(enabled: Boolean) = withService { it.setSubtitlesEnabled(enabled) }

    fun selectSubtitleTrack(trackId: String?) = withService { it.selectSubtitleTrack(trackId) }

    fun leaveRoom() {
        service?.leaveRoom()
        pendingActions.clear()
        _roomState.value = RoomState()
        _connectionState.value = ConnectionState.Disconnected
        _remoteVideoTracks.value = emptyMap()
        _subtitleTracks.value = emptyList()
        _selectedSubtitleTrackId.value = null
    }

    private fun mirrorMeshState(playbackService: PlaybackForegroundService) {
        meshMirrorJob?.cancel()
        meshMirrorJob = viewModelScope.launch {
            playbackService.meshManager?.remoteVideoTracks?.collect {
                _remoteVideoTracks.value = it
            }
        }
    }

    override fun onCleared() {
        leaveRoom()
        clearMirrors()
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }
        super.onCleared()
    }
}
