package org.adevelop.rcall.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

class PeerConnectionManager(
    private val appContext: Context,
    private val factory: PeerConnectionFactory,
    private val iceServers: List<PeerConnection.IceServer>,
    private val eglCtx: EglBase.Context,
    private val remoteSink: VideoSink,
    private val localSink: VideoSink,
    private val enableVideo: Boolean,
    private val relayOnly: Boolean = false
) {
    private var pc: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    var isPolite: Boolean = false
    private var makingOffer = false
    private var isSettingRemoteAnswer = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private val executor = Executors.newSingleThreadExecutor()

    private fun execute(block: () -> Unit) {
        executor.execute(block)
    }

    private class SdpResultObserver(
        private val onSuccess: ((SessionDescription) -> Unit)? = null,
        private val onFailure: ((String?) -> Unit)? = null
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            onSuccess?.invoke(desc)
        }

        override fun onSetSuccess() {
        }

        override fun onCreateFailure(error: String?) {
            onFailure?.invoke(error)
        }

        override fun onSetFailure(error: String?) {
            onFailure?.invoke(error)
        }
    }

    fun createPeer(
        onIce: (IceCandidate) -> Unit,
        onRenegotiationNeeded: () -> Unit,
        onConnected: () -> Unit,
        onDisconnected: (reason: String?) -> Unit
    ) = execute {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE

        keyType = PeerConnection.KeyType.ECDSA

        if (relayOnly) {
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
    }

        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                Log.d("PeerConnection", "Signaling state changed: $newState")
                isSettingRemoteAnswer = newState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d("PeerConnection", "ICE state changed: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> onDisconnected(newState.name)
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) = onIce(candidate)
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                Log.d("PeerConnectionManager", "onAddTrack: track ID = ${track?.id()}, kind = ${track?.kind()}")

                if (track is VideoTrack) {
                    Log.d("PeerConnectionManager", "VideoTrack received. Attaching to remoteSink.")
                    track.addSink(remoteSink)
                    track.setEnabled(true)
                } else if (track is AudioTrack) {
                    Log.d("PeerConnectionManager", "AudioTrack received. Enabling it.")
                    track.setEnabled(true)
                }
            }
            override fun onDataChannel(p0: DataChannel?) {}

            override fun onRenegotiationNeeded() {
                Log.d("PeerConnection", "onRenegotiationNeeded triggered")
                onRenegotiationNeeded()
            }
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddStream(p0: MediaStream?) {}
        })

        if (pc == null) {
            throw IllegalStateException("PeerConnection create failed.")
        }

        initLocalAudioTrack()
        if (enableVideo) {
            initLocalVideoTrack()
        }
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onFailure: (String?) -> Unit) = execute {
        if (makingOffer) {
            Log.w("PeerConnectionManager", "Skipping createOffer as one is already in progress.")
            return@execute
        }

        makingOffer = true
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc?.createOffer(SdpResultObserver(
            onSuccess = { offer ->
                pc?.setLocalDescription(SdpResultObserver(
                    onFailure = {
                        makingOffer = false
                        onFailure(it)
                    }
                ), offer)
                onSuccess(offer)
            },
            onFailure = {
                makingOffer = false
                onFailure(it)
            }
        ), constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onFailure: (String?) -> Unit) = execute {
        val constraints = MediaConstraints()
        pc?.createAnswer(SdpResultObserver(
            onSuccess = { answer ->
                pc?.setLocalDescription(SdpResultObserver(
                    onFailure = { onFailure(it) }
                ), answer)
                onSuccess(answer)
            },
            onFailure = { onFailure(it) }
        ), constraints)
    }

    fun setRemoteDescription(
        desc: SessionDescription,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) = execute {
        val observer = object : SdpObserver {
            override fun onSetSuccess() {
                Log.d("PeerConnectionManager", "Remote description set successfully. Draining ${pendingIceCandidates.size} pending ICE candidates.")
                pendingIceCandidates.forEach { candidate ->
                    pc?.addIceCandidate(candidate)
                }
                pendingIceCandidates.clear()
                onSuccess()
            }

            override fun onSetFailure(error: String?) {
                onFailure(error)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }
        val state = pc?.signalingState()
        if (desc.type == SessionDescription.Type.OFFER && state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            val offerCollision = !isPolite && !isSettingRemoteAnswer
            if (offerCollision) {
                Log.w("PeerConnectionManager", "Offer collision detected. Impolite peer refusing remote offer.")
                onFailure("Offer collision")
                return@execute
            } else {
                Log.d("PeerConnectionManager", "Offer collision detected. Polite peer rolling back to accept.")
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        pc?.setRemoteDescription(observer, desc)
                    }
                    override fun onSetFailure(error: String?) { onFailure(error) }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                return@execute
            }
        }

        pc?.setRemoteDescription(observer, desc)
    }


    fun addIceCandidate(candidate: IceCandidate) = execute {
        if (pc?.remoteDescription != null) {
            Log.d("PeerConnectionManager", "Adding ICE candidate immediately.")
            pc?.addIceCandidate(candidate)
        } else {
            Log.d("PeerConnectionManager", "Remote description is not set. Queuing ICE candidate.")
            pendingIceCandidates.add(candidate)
        }
    }

    fun toggleVideo(enabled: Boolean) = execute {
        localVideoTrack?.setEnabled(enabled)
        Log.d("PeerConnectionManager", "Local video track set to enabled: $enabled")
    }

    fun switchCamera() = execute {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun close() = execute {
        runCatching { videoCapturer?.stopCapture() }.onFailure { Log.e("PCM", "stopCapture failed", it) }
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        videoSource?.dispose()
        videoSource = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        audioSource?.dispose()
        audioSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        pc?.close()
        pc = null
    }


    private fun initLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            // Echo Cancellation - prevents the other person's voice from being played back to them.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            // High-pass Filter - removes low-frequency rumble.
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        Log.d("PeerConnectionManager", "Creating audio source with constraints: $audioConstraints")

        // Apply these constraints when creating the audio source.
        audioSource = factory.createAudioSource(audioConstraints)

        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        pc?.addTrack(localAudioTrack)
    }

    private fun initLocalVideoTrack() {
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglCtx)
        videoCapturer = createBestCapturer()?.also { capturer ->
            val source = factory.createVideoSource(capturer.isScreencast)
            videoSource = source
            capturer.initialize(surfaceHelper, appContext, source.capturerObserver)
            capturer.startCapture(640, 480, 30)

            localVideoTrack = factory.createVideoTrack("video0", source)
            localVideoTrack?.addSink(localSink)
            pc?.addTrack(localVideoTrack)
        }
        if (videoCapturer == null) {
            Log.w("PeerConnectionManager", "Video capturer could not be created.")
        }
    }

    private fun createBestCapturer(): VideoCapturer? {
        return if (Camera2Enumerator.isSupported(appContext)) {
            createCapturer(Camera2Enumerator(appContext))
        } else {
            createCapturer(Camera1Enumerator(true))
        }
    }

    private fun createCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        return enumerator.createCapturer(deviceName, null)
    }
}
