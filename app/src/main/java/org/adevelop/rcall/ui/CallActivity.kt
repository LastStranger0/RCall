package org.adevelop.rcall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import org.adevelop.rcall.BuildConfig
import org.adevelop.rcall.signaling.SignalingClient
import org.adevelop.rcall.webrtc.PeerConnectionManager
import org.adevelop.rcall.webrtc.RtcEnv
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CallActivity : ComponentActivity(), SignalingClient.Listener {

    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private var pcm: PeerConnectionManager? = null

    private val sig: SignalingClient by lazy {
        SignalingClient(BuildConfig.WS_BASE, lifecycleScope, this)
    }

    private val pcFactory get() = RtcEnv.factory
    private val eglCtx get() = RtcEnv.eglCtx
    private val myPeerId: String by lazy { sig.getPeerId() }
    private val roomId: String by lazy { intent?.getStringExtra("room") ?: "room-demo-${UUID.randomUUID().toString().take(4)}" }

    private val isClosing = AtomicBoolean(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            startCallFlow(cameraEnabled = cameraGranted)
        } else {
            Toast.makeText(this, "Требуется разрешение на микрофон.", Toast.LENGTH_LONG).show()
            safeFinish()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupUI()
        requestNeededPermissions()
    }

    private fun setupUI() {
        val container = FrameLayout(this)
        remoteView = SurfaceViewRenderer(this).apply {
            init(eglCtx, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        localView = SurfaceViewRenderer(this).apply {
            init(eglCtx, null)
            setMirror(true)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setZOrderMediaOverlay(true)
            val w = 120.dp()
            val h = 160.dp()
            layoutParams = FrameLayout.LayoutParams(w, h, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = 16.dp()
                bottomMargin = 16.dp()
            }
        }
        container.addView(remoteView)
        container.addView(localView)
        setContentView(container)
    }

    private fun requestNeededPermissions() {
        val requiredPermissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        requestPermissionsLauncher.launch(requiredPermissions)
    }



    private fun startCallFlow(cameraEnabled: Boolean) {
        sig.connect(roomId)
    }

    private fun initializeWebRtc(cameraEnabled: Boolean) {
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

        pcm = PeerConnectionManager(
            appContext = applicationContext,
            factory = pcFactory,
            eglCtx = eglCtx,
            iceServers = iceServers,
            remoteSink = remoteView,
            localSink = localView,
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
            val cameraPermission =
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
            onSuccess = { Log.d("CallActivity", "Set remote answer success. Connection should be established.") },
            onFailure = { error -> Log.e("CallActivity", "Set Remote Answer failed: $error") }
        )
    }

    override fun onIce(from: String, candidate: IceCandidate) {
        pcm?.addIceCandidate(candidate)
    }

    override fun onClosed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            safeFinish()
        }
    }

    private fun safeFinish() {
        if (isClosing.compareAndSet(false, true)) {
            Log.d("CallActivity", "Finishing activity...")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CallActivity", "onDestroy called")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sig.leave()
        pcm?.close()
        localView.release()
        remoteView.release()
    }
}
