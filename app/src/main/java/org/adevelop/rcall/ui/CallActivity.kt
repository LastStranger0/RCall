package org.adevelop.rcall.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import org.adevelop.rcall.BuildConfig
import org.adevelop.rcall.R
import org.adevelop.rcall.data.TrustClient
import org.adevelop.rcall.service.CallService
import org.adevelop.rcall.signaling.SignalingClient
import org.adevelop.rcall.ui.theme.RCallTheme
import org.adevelop.rcall.ui.theme.Red
import org.adevelop.rcall.ui.theme.White
import org.adevelop.rcall.webrtc.PeerConnectionManager
import org.adevelop.rcall.webrtc.RtcAudioManager
import org.adevelop.rcall.webrtc.RtcEnv
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CallActivity : ComponentActivity(), SignalingClient.Listener {

    private var pcm: PeerConnectionManager? = null

    private val rtcAudioManager: RtcAudioManager by lazy {
        RtcAudioManager(applicationContext)
    }

    // Add state for video
    private var isVideoEnabled by mutableStateOf(true)

    private val sig: SignalingClient by lazy {
        SignalingClient(BuildConfig.WS_BASE, lifecycleScope, TrustClient().getSecureApiService(this.applicationContext), this)
    }

    private val eglCtx get() = RtcEnv.eglCtx
    private val myPeerId: String by lazy { sig.getPeerId() }
    private val roomId: String by lazy { intent?.getStringExtra("room") ?: "room-demo-${UUID.randomUUID().toString().take(4)}" }

    private val localRenderer: SurfaceViewRenderer by lazy { SurfaceViewRenderer(this) }
    private val remoteRenderer: SurfaceViewRenderer by lazy { SurfaceViewRenderer(this) }

    private val isClosing = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val bluetoothGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true

        if (bluetoothGranted) {
            rtcAudioManager.checkInitialBluetoothState()
        }

        rtcAudioManager.start()

        if (micGranted) {
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            startCallFlow(cameraEnabled = cameraGranted)
        } else {
            Toast.makeText(this, "Требуется разрешение на микрофон.", Toast.LENGTH_LONG).show()
            safeFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RCallTheme {
                HandlePermissions()
                CallScreen(
                    onClose = { safeFinish() },
                    onToggleVideo = {
                        isVideoEnabled = !isVideoEnabled
                        pcm?.toggleVideo(isVideoEnabled)
                    },
                    isVideoEnabled = isVideoEnabled, // Pass state to UI
                    local = localRenderer,
                    remote = remoteRenderer,
                )
            }
        }
    }

    // --- Composable-функции для нашего UI ---

    @Composable
    fun CallScreen(
        onClose: () -> Unit,
        onToggleVideo: () -> Unit, // Add this callback
        isVideoEnabled: Boolean,  // Add this state
        local: SurfaceViewRenderer,
        remote: SurfaceViewRenderer
    ) {
        DisposableEffect(Unit) {
            // Init renderers when the composable enters the screen
            local.init(eglCtx, null)
            remote.init(eglCtx, null)

            onDispose {
                // The renderers will be released in the Activity's onDestroy.
                // No need to release them here, as this onDispose might be called
                // during configuration changes, which we don't want.
                Log.d("CallScreen", "Composable disposed.")
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { remote },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                }
            )
            AndroidView(
                factory = { local },
                modifier = Modifier
                    .padding(bottom = 88.dp)
                    .size(width = 120.dp, height = 160.dp)
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(CircleShape),
                update = { view ->
                    view.setMirror(true)
                    view.setEnableHardwareScaler(true)
                    view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    view.setZOrderMediaOverlay(true)
                }
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                CallActionButton(
                    onClick = {
                        pcm?.switchCamera()
                    },
                    iconRes = R.drawable.ic_switch_camera,
                    backgroundColor = Color.DarkGray,
                    iconTint = White,
                    contentDescription = "Switch Camera"
                )

                // Video Toggle Button
                CallActionButton(
                    onClick = onToggleVideo,
                    iconRes = if (isVideoEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
                    backgroundColor = if (isVideoEnabled) Color.DarkGray else White,
                    iconTint = if (isVideoEnabled) White else Color.Black,
                    contentDescription = "Toggle Video"
                )

                // Close Button
                CallActionButton(
                    onClick = onClose,
                    iconRes = R.drawable.ic_call_end,
                    backgroundColor = Red,
                    contentDescription = "Hang Up"
                )
            }
        }
    }

    @Preview
    @Composable
    private fun CallActionButtonPreview() {
        RCallTheme {
            CallActionButton(
                onClick = { /* Handle click */ },
                iconRes = R.drawable.ic_switch_camera,
                backgroundColor = Color.DarkGray,
                iconTint = White,
                contentDescription = "Switch Camera"
            )
        }
    }

    @Composable
    private fun CallActionButton(
        onClick: () -> Unit,
        iconRes: Int,
        backgroundColor: Color,
        modifier: Modifier = Modifier,
        contentDescription: String? = null,
        iconTint: Color = White // Default tint for icons
    ) {
        Box(
            modifier = modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(36.dp)
            )
        }
    }

    @Composable
    private fun HandlePermissions() {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    requestNeededPermissions()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    // --- Логика Activity ---

    private fun requestNeededPermissions() {
        val requiredPermissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            startCallFlow(cameraEnabled = cameraGranted)
        } else {
            requestPermissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun startCallService() {
        Log.d("CallActivity", "Starting CallService")
        val serviceIntent = Intent(this, CallService::class.java)
        // Используем startForegroundService для Android 8 (Oreo) и выше,
        // чтобы избежать исключения IllegalStateException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopCallService() {
        Log.d("CallActivity", "Stopping CallService")
        val serviceIntent = Intent(this, CallService::class.java)
        stopService(serviceIntent)
    }

    private fun startCallFlow(cameraEnabled: Boolean) {
        startCallService()
        pcm?.toggleVideo(cameraEnabled)
        sig.connect(roomId)
    }

    private fun initializeWebRtc(cameraEnabled: Boolean) {
        // Проверяем, доступны ли рендереры, перед инициализацией
        val localSink = this.localRenderer
        val remoteSink = this.remoteRenderer

        if (pcm != null) {
            Log.w("CallActivity", "WebRTC уже инициализирован.")
            return
        }

        Log.d("CallActivity", "Инициализация WebRTC...")
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
                .setUsername(BuildConfig.TURN_USER)
                .setPassword(BuildConfig.TURN_PASS)
                .createIceServer()
        )
        val pcFactory = RtcEnv.factory

        pcm = PeerConnectionManager(
            appContext = applicationContext,
            factory = pcFactory,
            eglCtx = eglCtx,
            iceServers = iceServers,
            remoteSink = remoteSink,
            localSink = localSink,
            enableVideo = cameraEnabled,
            relayOnly = false
        ).also { manager ->
            manager.createPeer(
                onIce = { candidate -> sig.sendIce(candidate) },
                onRenegotiationNeeded = {
                    Log.d("CallActivity", "onRenegotiationNeeded triggered, but we will decide when to offer.")
                },
                onConnected = { runOnUiThread { Toast.makeText(this, "Соединено!", Toast.LENGTH_SHORT).show() } },
                onDisconnected = { reason -> onClosed("Соединение разорвано: $reason") }
            )
        }
    }

    override fun onConnectedToSignaling() {
        Log.d("CallActivity", "Подключено к сигнальному серверу. Инициализируем WebRTC.")
        val cameraPermission =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        initializeWebRtc(cameraEnabled = cameraPermission)
    }

    override fun onPeerJoined(peerId: String) {
        Log.d("CallActivity", "Peer '$peerId' joined. My ID is '$myPeerId'.")
        if (pcm == null) {
            Log.w("CallActivity", "Получен onPeerJoined, но PCM еще не инициализирован. Игнорируем.")
            return
        }

        // Perfect Negotiation Logic
        pcm?.isPolite = myPeerId > peerId

        if (pcm?.isPolite == false) {
            Log.d("CallActivity", "Я impolite, создаю оффер для $peerId")
            pcm?.createOffer(
                onSuccess = { sdp -> sig.sendOffer(sdp) },
                onFailure = { error -> Log.e("CallActivity", "Create Offer failed: $error") }
            )
        } else {
            Log.d("CallActivity", "Я polite, жду оффер от $peerId")
        }
    }

    override fun onOffer(from: String, sdp: String) {
        Log.d("CallActivity", "Получен offer от $from")
        if (pcm == null) {
            Log.w("CallActivity", "Получен offer, но PCM еще не инициализирован. Инициализируем сейчас.")
            val cameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            initializeWebRtc(cameraEnabled = cameraPermission)
        }

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pcm?.setRemoteDescription(
            remoteSdp,
            onSuccess = {
                Log.d("CallActivity", "Set remote offer success. Создаем answer.")
                pcm?.createAnswer(
                    onSuccess = { answerSdp -> sig.sendAnswer(answerSdp) },
                    onFailure = { error -> Log.e("CallActivity", "Create Answer failed: $error") }
                )
            },
            onFailure = { error -> Log.e("CallActivity", "Set Remote Offer failed: $error") }
        )
    }

    override fun onAnswer(from: String, sdp: String) {
        Log.d("CallActivity", "Received answer from $from")
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pcm?.setRemoteDescription(
            remoteSdp,
            onSuccess = { Log.d("CallActivity", "Set remote answer success.") },
            onFailure = { error -> Log.e("CallActivity", "Set Remote Answer failed: $error") }
        )
    }

    override fun onIce(from: String, candidate: IceCandidate) {
        pcm?.addIceCandidate(candidate)
    }

    override fun onClosed(reason: String) {
        // This method is called when the WebSocket connection is closed.
        // The SignalingClient will attempt to reconnect automatically.
        // We should not finish the activity here, as that would terminate the call.
        // Only finish the activity when the user explicitly hangs up.
        Log.d("CallActivity", "Signaling connection closed: $reason. Awaiting reconnection.")
        runOnUiThread {
            Toast.makeText(this, "Connection lost, attempting to reconnect...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeFinish() {
        if (isClosing.compareAndSet(false, true)) {
            Log.d("CallActivity", "Finishing activity...")
            // Stop the foreground service
            stopCallService()
            // Gracefully leave the signaling room
            sig.leave()
            // Close the peer connection
            pcm?.close()
            // Finish the activity
            finish()
        }
    }


    override fun onDestroy() {
        // --- START: DEACTIVATE AUDIO MANAGER ---
        rtcAudioManager.stop()
        // --- END: DEACTIVATE AUDIO MANAGER ---

        Log.d("CallActivity", "onDestroy called")
        if (isFinishing || !isChangingConfigurations) {
            localRenderer.release()
            remoteRenderer.release()
            stopCallService()
            sig.leave()
            pcm?.close()
        }
        super.onDestroy()
    }
}
