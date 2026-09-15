package com.watchparty.rtc

import android.content.Context
import com.watchparty.net.PeerJoined
import com.watchparty.net.PeerLeft
import com.watchparty.net.RtcSignalEvent
import com.watchparty.net.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRtcMeshManager — owns a full-mesh set of PeerConnections, one per remote
 * participant (correct choice for 2-4 people; do not scale this pattern past
 * ~6 without moving to an SFU).
 *
 * Echo/feedback note: this class enables WebRTC's built-in AEC3 + AGC via the
 * MediaConstraints below. That mitigates but does NOT eliminate speaker-driven
 * feedback in a 4-way mesh — strongly encourage headphones in the UI.
 *
 * Lifecycle:
 *   1. init(context) once, e.g. from the foreground Service's onCreate.
 *   2. call start() after SignalingClient has joined/created the room.
 *   3. Mesh connections are created reactively as PEER_JOINED events arrive
 *      and torn down on PEER_LEFT.
 *   4. dispose() when leaving the Watch Room entirely.
 */
class WebRtcMeshManager(
    private val appContext: Context,
    private val signalingClient: SignalingClient,
    val eglBase: EglBase = EglBase.create(),
    private val ownsEglBase: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate)
) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var audioSource: AudioSource
    private lateinit var localAudioTrack: AudioTrack
    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    var localVideoTrack: VideoTrack? = null
        private set

    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val listenerJobs = mutableListOf<Job>()

    companion object {
        const val TURN_URL = "turn:earlswatchparty.metered.live:80"
        const val TURN_USERNAME = "vIt2x-6HMAJwsd9acKNjrXg3u--oCDHsaslSk_wusyJyv3ls"
        const val TURN_PASSWORD = "vIt2x-6HMAJwsd9acKNjrXg3u--oCDHsaslSk_wusyJyv3ls"
    }

    private val iceServers: List<PeerConnection.IceServer> by lazy {
        buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
            add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())
            add(PeerConnection.IceServer.builder("stun:earlswatchparty.metered.live:80").createIceServer())
            if (TURN_URL.isNotBlank() && TURN_USERNAME.isNotBlank()) {
                // Port 80 (UDP and TCP)
                add(
                    PeerConnection.IceServer.builder("turn:earlswatchparty.metered.live:80")
                        .setUsername(TURN_USERNAME)
                        .setPassword(TURN_PASSWORD)
                        .createIceServer()
                )
                add(
                    PeerConnection.IceServer.builder("turn:earlswatchparty.metered.live:80?transport=tcp")
                        .setUsername(TURN_USERNAME)
                        .setPassword(TURN_PASSWORD)
                        .createIceServer()
                )
                // Port 443 (TURNS / TLS over port 443 - highest firewall penetration)
                add(
                    PeerConnection.IceServer.builder("turns:earlswatchparty.metered.live:443?transport=tcp")
                        .setUsername(TURN_USERNAME)
                        .setPassword(TURN_PASSWORD)
                        .createIceServer()
                )
                // Port 3478 standard TURN
                add(
                    PeerConnection.IceServer.builder("turn:earlswatchparty.metered.live:3478")
                        .setUsername(TURN_USERNAME)
                        .setPassword(TURN_PASSWORD)
                        .createIceServer()
                )
            }
        }
    }

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    /** peerId -> remote VideoTrack, for the floating camera tile UI to render. */
    val remoteVideoTracks = _remoteVideoTracks.asStateFlow()

    private val _remoteAudioState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** peerId -> is-receiving-audio, purely informational for UI indicators. */
    val remoteAudioState = _remoteAudioState.asStateFlow()

    // -- Setup -------------------------------------------------

    fun init(
        useFrontCamera: Boolean = true,
        enableCamera: Boolean = true,
        enableMic: Boolean = true
    ) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        if (enableMic) {
            setupLocalAudio()
        }
        if (enableCamera) {
            setupLocalVideo(useFrontCamera)
        }
    }

    private fun setupLocalAudio() {
        val audioConstraints = MediaConstraints().apply {
            // Critical for a speaker-driven mesh: enable AEC/AGC/NS explicitly.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
    }

    private fun setupLocalVideo(useFrontCamera: Boolean) {
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (useFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: return

        val capturer = enumerator.createCapturer(deviceName, null) ?: return
        videoCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        localVideoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, appContext, localVideoSource!!.capturerObserver)
        capturer.startCapture(640, 360, 24) // modest resolution — this is a floating tile, not the main view

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", localVideoSource)
    }

    // -- Reactive wiring to SignalingClient -------------------------------------------------

    fun start() {
        listenerJobs += scope.launch {
            signalingClient.peerJoined.collect { event -> onPeerJoined(event) }
        }
        listenerJobs += scope.launch {
            signalingClient.peerLeft.collect { event -> onPeerLeft(event) }
        }
        listenerJobs += scope.launch {
            signalingClient.rtcSignals.collect { event -> onRtcSignal(event) }
        }
    }

    /**
     * PEER_JOINED fires two ways:
     *  - On initial JOIN_ACCEPTED, once per existing participant (we are the "new" peer,
     *    so by convention the NEW joiner initiates offers to existing peers).
     *  - On a later PEER_JOINED broadcast (someone else joined after us) — in that case
     *    the newcomer will initiate to us, so we just wait for their offer.
     */
    private fun onPeerJoined(event: PeerJoined) {
        val selfId = signalingClient.roomState.value.selfClientId ?: return
        val isExistingPeerFromJoinAccepted = event.name.isEmpty() // convention used by SignalingClient
        if (isExistingPeerFromJoinAccepted && event.clientId != selfId) {
            initiateOfferTo(event.clientId)
        }
        // Otherwise: wait for their RTC_OFFER — see onRtcSignal.
    }

    private fun onPeerLeft(event: PeerLeft) {
        peerConnections.remove(event.clientId)?.close()
        _remoteVideoTracks.value = _remoteVideoTracks.value - event.clientId
        _remoteAudioState.value = _remoteAudioState.value - event.clientId
    }

    private fun onRtcSignal(event: RtcSignalEvent) {
        when (event) {
            is RtcSignalEvent.Offer -> handleRemoteOffer(event.fromId, event.sdp)
            is RtcSignalEvent.Answer -> handleRemoteAnswer(event.fromId, event.sdp)
            is RtcSignalEvent.IceCandidate -> handleRemoteIceCandidate(
                event.fromId, event.sdpMid, event.sdpMLineIndex, event.candidate
            )
        }
    }

    // -- Peer connection lifecycle -------------------------------------------------

    private fun getOrCreatePeerConnection(peerId: String): PeerConnection {
        peerConnections[peerId]?.let { return it }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendRtcIceCandidate(
                    targetId = peerId,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            }

            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let { track ->
                    _remoteVideoTracks.value = _remoteVideoTracks.value + (peerId to track)
                }
                _remoteAudioState.value = _remoteAudioState.value + (peerId to stream.audioTracks.isNotEmpty())
            }

            override fun onRemoveStream(stream: MediaStream) {
                _remoteVideoTracks.value = _remoteVideoTracks.value - peerId
                _remoteAudioState.value = _remoteAudioState.value - peerId
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    peerConnections.remove(peerId)
                    _remoteVideoTracks.value = _remoteVideoTracks.value - peerId
                    _remoteAudioState.value = _remoteAudioState.value - peerId
                }
            }

            // No-ops we don't need to act on but must implement.
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver, p1: Array<out MediaStream>) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: error("Failed to create PeerConnection for $peerId")

        // Attach local media
        if (::localAudioTrack.isInitialized) {
            pc.addTrack(localAudioTrack, listOf("watchparty_stream"))
        }
        localVideoTrack?.let { pc.addTrack(it, listOf("watchparty_stream")) }

        peerConnections[peerId] = pc
        return pc
    }

    private fun initiateOfferTo(peerId: String) {
        val pc = getOrCreatePeerConnection(peerId)
        val constraints = MediaConstraints()

        pc.createOffer(object : SdpObserver by NoOpSdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(NoOpSdpObserver, desc)
                signalingClient.sendRtcOffer(peerId, desc.description)
            }
        }, constraints)
    }

    private fun handleRemoteOffer(fromId: String, sdp: String) {
        val pc = getOrCreatePeerConnection(fromId)
        pc.setRemoteDescription(NoOpSdpObserver, SessionDescription(SessionDescription.Type.OFFER, sdp))

        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserver by NoOpSdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(NoOpSdpObserver, desc)
                signalingClient.sendRtcAnswer(fromId, desc.description)
            }
        }, constraints)
    }

    private fun handleRemoteAnswer(fromId: String, sdp: String) {
        val pc = peerConnections[fromId] ?: return
        pc.setRemoteDescription(NoOpSdpObserver, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun handleRemoteIceCandidate(fromId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val pc = peerConnections[fromId] ?: return
        pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    // -- Local media controls (mute/camera toggle for in-room UI) --------

    fun setMicEnabled(enabled: Boolean) {
        if (::localAudioTrack.isInitialized) {
            localAudioTrack.setEnabled(enabled)
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    // -- Teardown -------------------------------------------------

    fun dispose() {
        listenerJobs.forEach { it.cancel() }
        listenerJobs.clear()

        peerConnections.values.forEach { it.close() }
        peerConnections.clear()

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoSource?.dispose()
        if (::audioSource.isInitialized) {
            audioSource.dispose()
        }
        if (ownsEglBase) {
            eglBase.release()
        }
    }
}

/** Default no-op SdpObserver so call sites only override what they need. */
private object NoOpSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
