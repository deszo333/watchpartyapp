package com.watchparty.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// ============================================================================
// Wire models — mirror server.js message shapes exactly.
// ============================================================================

@Serializable
data class ParticipantDto(
    val id: String,
    val name: String,
    val ready: Boolean,
    val buffering: Boolean,
    val isHost: Boolean
)

@Serializable
data class PlaybackDto(
    val state: String, // "playing" | "paused"
    val positionMs: Long,
    val updatedAtMs: Long,
    val sourceUrl: String? = null
)

/** Top-level connection lifecycle state, exposed to the UI layer. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

/** Authoritative room snapshot — the UI/ViewModel should render straight off this. */
data class RoomState(
    val roomCode: String? = null,
    val selfClientId: String? = null,
    val hostId: String? = null,
    val participants: List<ParticipantDto> = emptyList(),
    val playback: PlaybackDto = PlaybackDto("paused", 0, 0, null)
) {
    val isSelfHost: Boolean get() = selfClientId != null && selfClientId == hostId
}

data class ChatEvent(val fromId: String, val fromName: String, val text: String, val sentAtMs: Long)

/** One-shot sync signals the SyncEngine consumes to drive ExoPlayer corrections. */
sealed class PlaybackSyncEvent {
    data class Play(val positionMs: Long, val serverTimeMs: Long) : PlaybackSyncEvent()
    data class Pause(val positionMs: Long, val serverTimeMs: Long) : PlaybackSyncEvent()
    data class Seek(val positionMs: Long, val serverTimeMs: Long) : PlaybackSyncEvent()
    data class Ping(val positionMs: Long, val serverTimeMs: Long, val state: String) : PlaybackSyncEvent()
    data class AutoPause(val positionMs: Long, val bufferingClientIds: List<String>) : PlaybackSyncEvent()
    data object BufferGateClear : PlaybackSyncEvent()
    data class SourceChanged(val sourceUrl: String) : PlaybackSyncEvent()
    data class Snapshot(val playback: PlaybackDto) : PlaybackSyncEvent()
}

/** WebRTC signaling envelopes, forwarded untouched to the WebRtcMeshManager. */
sealed class RtcSignalEvent {
    data class Offer(val fromId: String, val sdp: String) : RtcSignalEvent()
    data class Answer(val fromId: String, val sdp: String) : RtcSignalEvent()
    data class IceCandidate(
        val fromId: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String
    ) : RtcSignalEvent()
}

data class PeerJoined(val clientId: String, val name: String)
data class PeerLeft(val clientId: String)

/**
 * SignalingClient — owns the single WebSocket connection to the room server.
 *
 * Exposes:
 *  - connectionState: StateFlow<ConnectionState>   (UI: show connecting/retry banners)
 *  - roomState:       StateFlow<RoomState>          (UI: roster, host badge, ready states)
 *  - chatEvents:       SharedFlow<ChatEvent>         (UI: chat drawer)
 *  - playbackSync:     SharedFlow<PlaybackSyncEvent> (consumed by SyncEngine, not UI directly)
 *  - rtcSignals:       SharedFlow<RtcSignalEvent>    (consumed by WebRtcMeshManager)
 *  - peerJoined/peerLeft: SharedFlow                 (drives mesh connection setup/teardown)
 *
 * This class is deliberately Activity/Compose-agnostic so it can live inside a
 * foreground Service and survive configuration changes / backgrounding.
 */
class SignalingClient(
    private var serverUrl: String, // e.g. "ws://10.0.2.2:8080" or "wss://..."
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS) // matches server heartbeat cadence
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS is long-lived; no read timeout
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = false
    private var pendingJoin: PendingJoin? = null

    fun updateServerUrl(newUrl: String) {
        if (serverUrl != newUrl.trim()) {
            serverUrl = newUrl.trim()
            if (webSocket != null) {
                disconnect()
            }
        }
    }

    // -- Public reactive surfaces -------------------------------------------------

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _roomState = MutableStateFlow(RoomState())
    val roomState = _roomState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 32)
    val chatEvents = _chatEvents.asSharedFlow()

    private val _playbackSync = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = 32)
    val playbackSync = _playbackSync.asSharedFlow()

    private val _rtcSignals = MutableSharedFlow<RtcSignalEvent>(extraBufferCapacity = 64)
    val rtcSignals = _rtcSignals.asSharedFlow()

    private val _peerJoined = MutableSharedFlow<PeerJoined>(extraBufferCapacity = 8)
    val peerJoined = _peerJoined.asSharedFlow()

    private val _peerLeft = MutableSharedFlow<PeerLeft>(extraBufferCapacity = 8)
    val peerLeft = _peerLeft.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    private sealed class PendingJoin {
        data class Create(val name: String) : PendingJoin()
        data class Join(val roomCode: String, val name: String) : PendingJoin()
    }

    // -- Connection lifecycle -------------------------------------------------

    fun createRoom(displayName: String) {
        pendingJoin = PendingJoin.Create(displayName)
        connect()
    }

    fun joinRoom(roomCode: String, displayName: String) {
        pendingJoin = PendingJoin.Join(roomCode.uppercase(), displayName)
        connect()
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun connect() {
        shouldReconnect = true
        _connectionState.value = ConnectionState.Connecting
        val request = Request.Builder().url(serverUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            reconnectAttempt = 0
            _connectionState.value = ConnectionState.Connected
            when (val pj = pendingJoin) {
                is PendingJoin.Create -> sendRaw(mapOf("type" to "CREATE_ROOM", "name" to pj.name))
                is PendingJoin.Join -> sendRaw(
                    mapOf("type" to "JOIN_ROOM", "roomCode" to pj.roomCode, "name" to pj.name)
                )
                null -> Unit
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleServerMessage(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.Disconnected
            maybeReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            _connectionState.value = ConnectionState.Failed(t.message ?: "connection_failed")
            maybeReconnect()
        }
    }

    /** Exponential backoff reconnect, capped, only while a room session is desired. */
    private fun maybeReconnect() {
        if (!shouldReconnect) return
        externalScope.launch {
            reconnectAttempt++
            val backoffMs = minOf(1000L * (1 shl minOf(reconnectAttempt, 5)), 16_000L)
            delay(backoffMs)
            if (shouldReconnect) connect()
        }
    }

    // -- Outbound messages -------------------------------------------------

    fun setReady(ready: Boolean) = sendRaw(mapOf("type" to "SET_READY", "ready" to ready))

    fun setPlaybackSource(url: String) =
        sendRaw(mapOf("type" to "PLAYBACK_SET_SOURCE", "sourceUrl" to url))

    fun sendPlay(positionMs: Long) =
        sendRaw(mapOf("type" to "PLAYBACK_PLAY", "positionMs" to positionMs))

    fun sendPause(positionMs: Long) =
        sendRaw(mapOf("type" to "PLAYBACK_PAUSE", "positionMs" to positionMs))

    fun sendSeek(positionMs: Long) =
        sendRaw(mapOf("type" to "PLAYBACK_SEEK", "positionMs" to positionMs))

    /** Host calls this every ~2s while playing to drive client drift correction. */
    fun sendPlaybackPing(positionMs: Long) =
        sendRaw(mapOf("type" to "PLAYBACK_PING", "positionMs" to positionMs))

    fun sendBufferState(buffering: Boolean) =
        sendRaw(mapOf("type" to "BUFFER_STATE", "buffering" to buffering))

    fun sendChat(text: String) = sendRaw(mapOf("type" to "CHAT_MESSAGE", "text" to text))

    fun sendRtcOffer(targetId: String, sdp: String) =
        sendRaw(mapOf("type" to "RTC_OFFER", "targetId" to targetId, "sdp" to sdp))

    fun sendRtcAnswer(targetId: String, sdp: String) =
        sendRaw(mapOf("type" to "RTC_ANSWER", "targetId" to targetId, "sdp" to sdp))

    fun sendRtcIceCandidate(
        targetId: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
        candidate: String
    ) = sendRaw(
        mapOf(
            "type" to "RTC_ICE_CANDIDATE",
            "targetId" to targetId,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex,
            "candidate" to candidate
        )
    )

    private fun sendRaw(fields: Map<String, Any?>) {
        val jsonObj = JsonObject(fields.mapValues { (_, v) -> toJsonElement(v) })
        webSocket?.send(json.encodeToString(JsonObject.serializer(), jsonObj))
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive(null as String?)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    // -- Inbound message routing -------------------------------------------------

    private fun handleServerMessage(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {

            "ROOM_CREATED" -> {
                val clientId = obj["clientId"]?.jsonPrimitive?.content
                val roomCode = obj["roomCode"]?.jsonPrimitive?.content
                val hostId = obj["hostId"]?.jsonPrimitive?.content
                _roomState.value = _roomState.value.copy(
                    roomCode = roomCode,
                    selfClientId = clientId,
                    hostId = hostId
                )
            }

            "JOIN_ACCEPTED" -> {
                val clientId = obj["clientId"]?.jsonPrimitive?.content
                val roomCode = obj["roomCode"]?.jsonPrimitive?.content
                val hostId = obj["hostId"]?.jsonPrimitive?.content
                val playback = obj["playback"]?.let {
                    runCatching { json.decodeFromJsonElement(PlaybackDto.serializer(), it) }.getOrNull()
                }
                _roomState.value = _roomState.value.copy(
                    roomCode = roomCode,
                    selfClientId = clientId,
                    hostId = hostId,
                    playback = playback ?: _roomState.value.playback
                )
                playback?.let { snap ->
                    if (!snap.sourceUrl.isNullOrBlank()) {
                        _playbackSync.tryEmit(PlaybackSyncEvent.Snapshot(snap))
                    }
                }
                // Existing peers — the mesh manager should initiate offers to each.
                val existingIds = obj["existingPeerIds"]?.let {
                    runCatching {
                        json.decodeFromJsonElement<List<String>>(it)
                    }.getOrNull()
                } ?: emptyList()
                existingIds.forEach { peerId -> _peerJoined.tryEmit(PeerJoined(peerId, "")) }
            }

            "ROOM_STATE" -> {
                val hostId = obj["hostId"]?.jsonPrimitive?.content
                val participants = obj["participants"]?.let {
                    runCatching {
                        json.decodeFromJsonElement(
                            ListSerializer(ParticipantDto.serializer()), it
                        )
                    }.getOrNull()
                } ?: emptyList()
                val playback = obj["playback"]?.let {
                    runCatching { json.decodeFromJsonElement(PlaybackDto.serializer(), it) }.getOrNull()
                }
                _roomState.value = _roomState.value.copy(
                    hostId = hostId ?: _roomState.value.hostId,
                    participants = participants,
                    playback = playback ?: _roomState.value.playback
                )
                playback?.let { snap ->
                    if (!snap.sourceUrl.isNullOrBlank()) {
                        _playbackSync.tryEmit(PlaybackSyncEvent.Snapshot(snap))
                    }
                }
            }

            "HOST_CHANGED" -> {
                val hostId = obj["hostId"]?.jsonPrimitive?.content
                _roomState.value = _roomState.value.copy(hostId = hostId)
            }

            "PEER_JOINED" -> {
                val id = obj["clientId"]?.jsonPrimitive?.content ?: return
                val name = obj["name"]?.jsonPrimitive?.content ?: ""
                _peerJoined.tryEmit(PeerJoined(id, name))
            }

            "PEER_LEFT" -> {
                val id = obj["clientId"]?.jsonPrimitive?.content ?: return
                _peerLeft.tryEmit(PeerLeft(id))
            }

            "PLAYBACK_SOURCE_CHANGED" -> {
                val url = obj["sourceUrl"]?.jsonPrimitive?.content ?: return
                _playbackSync.tryEmit(PlaybackSyncEvent.SourceChanged(url))
            }

            "PLAYBACK_PLAY" -> {
                val pos = obj["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val st = obj["serverTimeMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                _playbackSync.tryEmit(PlaybackSyncEvent.Play(pos, st))
            }

            "PLAYBACK_PAUSE" -> {
                val pos = obj["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val st = obj["serverTimeMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                _playbackSync.tryEmit(PlaybackSyncEvent.Pause(pos, st))
            }

            "PLAYBACK_SEEK" -> {
                val pos = obj["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val st = obj["serverTimeMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                _playbackSync.tryEmit(PlaybackSyncEvent.Seek(pos, st))
            }

            "PLAYBACK_PING" -> {
                val pos = obj["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val st = obj["serverTimeMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val state = obj["state"]?.jsonPrimitive?.content ?: "playing"
                _playbackSync.tryEmit(PlaybackSyncEvent.Ping(pos, st, state))
            }

            "PLAYBACK_AUTO_PAUSE" -> {
                val pos = obj["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val ids = obj["bufferingClientIds"]?.let {
                    runCatching {
                        json.decodeFromJsonElement<List<String>>(it)
                    }.getOrNull()
                } ?: emptyList()
                _playbackSync.tryEmit(PlaybackSyncEvent.AutoPause(pos, ids))
            }

            "BUFFER_GATE_CLEAR" -> _playbackSync.tryEmit(PlaybackSyncEvent.BufferGateClear)

            "CHAT_MESSAGE" -> {
                val fromId = obj["fromId"]?.jsonPrimitive?.content ?: return
                val fromName = obj["fromName"]?.jsonPrimitive?.content ?: ""
                val text = obj["text"]?.jsonPrimitive?.content ?: ""
                val sentAt = obj["sentAtMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                _chatEvents.tryEmit(ChatEvent(fromId, fromName, text, sentAt))
            }

            "RTC_OFFER" -> {
                val fromId = obj["fromId"]?.jsonPrimitive?.content ?: return
                val sdp = obj["sdp"]?.jsonPrimitive?.content ?: return
                _rtcSignals.tryEmit(RtcSignalEvent.Offer(fromId, sdp))
            }

            "RTC_ANSWER" -> {
                val fromId = obj["fromId"]?.jsonPrimitive?.content ?: return
                val sdp = obj["sdp"]?.jsonPrimitive?.content ?: return
                _rtcSignals.tryEmit(RtcSignalEvent.Answer(fromId, sdp))
            }

            "RTC_ICE_CANDIDATE" -> {
                val fromId = obj["fromId"]?.jsonPrimitive?.content ?: return
                val sdpMid = obj["sdpMid"]?.jsonPrimitive?.content
                val sdpMLineIndex = obj["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val candidate = obj["candidate"]?.jsonPrimitive?.content ?: return
                _rtcSignals.tryEmit(RtcSignalEvent.IceCandidate(fromId, sdpMid, sdpMLineIndex, candidate))
            }

            "ERROR" -> {
                val message = obj["message"]?.jsonPrimitive?.content ?: "unknown_error"
                val code = obj["code"]?.jsonPrimitive?.content
                val fullReason = if (code != null) "$code: $message" else message
                _errors.tryEmit(fullReason)
                _connectionState.value = ConnectionState.Failed(fullReason)
                shouldReconnect = false
            }
        }
    }
}
